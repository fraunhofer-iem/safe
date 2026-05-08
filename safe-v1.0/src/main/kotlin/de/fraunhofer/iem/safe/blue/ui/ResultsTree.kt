package de.fraunhofer.iem.safe.blue.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.safe.blue.PluginBundle
import de.fraunhofer.iem.safe.blue.comm.DataflowNotifier
import de.fraunhofer.iem.safe.blue.comm.ExplanationNotifier
import de.fraunhofer.iem.safe.blue.comm.ParseFileNotifier
import de.fraunhofer.iem.safe.blue.comm.ResultNotifier
import de.fraunhofer.iem.safe.blue.llm.LlmClient
import de.fraunhofer.iem.safe.blue.sast.Issue
import de.fraunhofer.iem.safe.blue.sast.IssueLocation
import de.fraunhofer.iem.safe.blue.sast.JsonParser
import de.fraunhofer.iem.safe.blue.sast.Results
import de.fraunhofer.iem.safe.blue.sast.SarifParser
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.*
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel


class ResultsTree(private val project: Project) : Tree() {

    private lateinit var results: Results
    private var currentIssue: Issue? = null
    val bus: MessageBus = project.messageBus

    /**
     * Public access to the loaded findings for code that needs to walk all
     * issues — primarily the study-mode telemetry (editor caret tracking,
     * pre-warm action). Returns an empty list before [addTreeNodes] runs.
     */
    fun allIssuesOrEmpty(): List<Issue> =
        if (::results.isInitialized) results.issues else emptyList()

    // ── Telemetry state ──────────────────────────────────────────────────
    private var telemetryFindingDisplayedAt: Long = 0L
    private var telemetrySelectedFindingId: String? = null

    init {

        cellRenderer = ResultsTreeRenderer()

        //Load last file
        if (PropertiesComponent.getInstance(project)
                .isValueSet("de.fraunhofer.iem.safe.file")
        ) {

            // Load SAST results from given path
            addTreeNodes(
                parseFile(
                    PropertiesComponent.getInstance(project)
                        .getValue("de.fraunhofer.iem.safe.file")!!,
                    project
                )
            )
            expandTree()
        } else {
            model = null
            this.emptyText.setText(
                PluginBundle.lazy("safe.ui.tree.empty").get(),
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            )
        }

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {

                val node = lastSelectedPathComponent as DefaultMutableTreeNode?

                val messageBus = project.getMessageBus()

                if (node != null && node.userObject is Issue) {

                    val issue = node.userObject as Issue
                    onFindingSelectedFromTree(issue, "tree_issue")
                    currentIssue = issue

                    // Also notify the explanation panel — it consults the
                    // persistent cache when the issue has no in-memory answer
                    // yet, so any previously generated explanation surfaces on
                    // a single click instead of forcing the user to invoke
                    // "Get Explanation" again.
                    val explanationPanel =
                        messageBus.syncPublisher(ExplanationNotifier.SHOW_EXPLANATION_TOPIC)
                    explanationPanel.showExplanation(issue)

                    val showDataFlow = messageBus.syncPublisher(DataflowNotifier.SHOW_EDITOR_TOPIC)
                    showDataFlow.showEditor(issue)

                    val resultPanel = messageBus.syncPublisher(ResultNotifier.SHOW_RESULT_TOPIC)
                    resultPanel.showResult(issue)
                } else if (node != null && node.userObject is IssueLocation) {

                    val parent = node.parent as DefaultMutableTreeNode?
                    val issue = parent!!.userObject as Issue

                    // Selecting the file-name (or message) node only navigates
                    // the side panels — it does NOT kick off a fresh LLM call.
                    // To generate an explanation, the user right-clicks the
                    // issue and picks "Get Explanation" from the popup menu.
                    onFindingSelectedFromTree(issue, "tree_location")
                    currentIssue = issue

                    val explanationPanel =
                        messageBus.syncPublisher(ExplanationNotifier.SHOW_EXPLANATION_TOPIC)
                    explanationPanel.showExplanation(issue)

                    val showDataFlow = messageBus.syncPublisher(DataflowNotifier.SHOW_EDITOR_TOPIC)
                    showDataFlow.showEditor(issue)

                    val resultPanel = messageBus.syncPublisher(ResultNotifier.SHOW_RESULT_TOPIC)
                    resultPanel.showResult(issue)
                }
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                    showContextMenu(e)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })

        //Subscribe to the response topic to get response
        bus.connect().subscribe(
            ParseFileNotifier.PARSE_SARIF_FILE,
            object : ParseFileNotifier {

                override fun parse(sastFile: String) {
                    addTreeNodes(parseFile(sastFile, project))
                    PropertiesComponent.getInstance(project)
                        .setValue("de.fraunhofer.iem.safe.file", sastFile)
                }
            })
        cellRenderer = ResultsTreeRenderer()
    }

    fun expandTree() {
        for (i in 0..<rowCount) {
            expandRow(i)
        }
    }

    fun refreshTree(project: Project) {
        //explainResult(project,  currentIssue,)
    }

    /**
     * Emits study-mode `finding.deselected` (with surface-word count and dwell)
     * for the previously displayed finding, then `finding.selected` for the
     * incoming one. Called from the tree's click handler — single source of
     * truth so both the Issue-row and Location-row click branches log the same
     * lifecycle bracket.
     */
    private fun onFindingSelectedFromTree(issue: Issue, source: String) {
        val recorder = de.fraunhofer.iem.safe.blue.study.TelemetryRecorder.getInstance(project)
        val incomingId = telemetryIdOf(issue)
        if (incomingId == telemetrySelectedFindingId) return

        // Bracket the previous finding's view first.
        val previousId = telemetrySelectedFindingId
        if (previousId != null && telemetryFindingDisplayedAt > 0L) {
            val previous = currentIssue
            recorder.record(
                event = "finding.deselected",
                findingId = previousId,
                data = mapOf(
                    "dwell_ms" to (System.currentTimeMillis() - telemetryFindingDisplayedAt)
                        .coerceAtLeast(0L),
                    "surface_word_count" to surfaceWordCountOf(previous),
                ),
            )
        }

        telemetrySelectedFindingId = incomingId
        telemetryFindingDisplayedAt = System.currentTimeMillis()
        recorder.record(
            event = "finding.selected",
            findingId = incomingId,
            data = mapOf(
                "source" to source,
                "type" to issue.type,
                "file" to issue.location.fileName,
                "start_line" to issue.location.startLine,
                "end_line" to issue.location.endLine,
                "has_explanation" to !(issue.explanation.isNullOrBlank() || issue.explanation == "N/A"),
            ),
        )
    }

    private fun telemetryIdOf(issue: Issue): String =
        "${issue.type}::${issue.location.fileName}::${issue.location.startLine}::${issue.location.endLine}"

    /**
     * Word count of the *surface* sections (overview + explanation) of the
     * given issue's response. The example and mitigation sections are
     * collapsed by default in v1's UI, so they're excluded — that keeps the
     * metric comparable to the new plugin's surface-word-count, which also
     * excludes the deep-dive.
     */
    private fun surfaceWordCountOf(issue: Issue?): Int {
        val response = issue?.explanation ?: return 0
        if (response.isBlank() || response == "N/A") return 0
        val markerKey = Regex(
            """\*\*(OVERVIEW|EXPLANATION)\*\*\s*:?\s*""",
            RegexOption.IGNORE_CASE,
        )
        val anyMarker = Regex("""\*\*[A-Za-z_]+\*\*""")
        val sections = mutableListOf<String>()
        for (m in markerKey.findAll(response)) {
            val start = m.range.last + 1
            val end = anyMarker.find(response, start)?.range?.first ?: response.length
            sections.add(response.substring(start, end).trim())
        }
        val text = sections.joinToString(" ")
        if (text.isBlank()) return 0
        return text.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    fun parseFile(resultsFile: String, project: Project): Results {

        if (resultsFile.endsWith(".json")
        ) {
            return JsonParser.parse(
                project, resultsFile
            )
        } else {
            return SarifParser.parse(
                project, resultsFile
            )
        }
    }

    fun addTreeNodes(results: Results) {

        this.results = results

        val rootNode = DefaultMutableTreeNode(results.filePath + " " + results.issues.count() + " Problems")
        model = DefaultTreeModel(rootNode)

        val resultsTreeNode =
            DefaultMutableTreeNode(results.tool + ": " + results.issues.count().toString() + " Problems")

        for (issue in results.issues) {

            val issueNode = DefaultMutableTreeNode(issue)
            //group by issue types
            //val issueNode = searchNode(resultsTreeNode, issue)

            val locationNode = DefaultMutableTreeNode(issue.location)
            issueNode!!.add(locationNode)

            val messageNode = DefaultMutableTreeNode(issue.message)
            locationNode.add(messageNode)

            resultsTreeNode.add(issueNode)
        }
        rootNode.add(resultsTreeNode)
    }

    private fun searchNode(root: DefaultMutableTreeNode, query: Issue): DefaultMutableTreeNode? {
        val e: Enumeration<*> = root.breadthFirstEnumeration()

        while (e.hasMoreElements()) {
            val node = e.nextElement() as DefaultMutableTreeNode

            if (node.getUserObject() is Issue) {
                val issue: Issue = node.getUserObject() as Issue
                if (query.type.contentEquals(issue.type)) {
                    return node
                }
            }
        }
        return DefaultMutableTreeNode(query)
    }


    private fun explainResult(project: Project, issue: Issue) {

        val level = PropertiesComponent.getInstance(project)
            .getValue("de.fraunhofer.iem.safe.expertiseValue") ?: "Intermediate"

        // Tag the issue as in-flight so the immediate showExplanation publish
        // (in mouseClicked) sees "N/A" and renders the loading placeholder
        // instead of the previously-cached HTML for some other issue.
        issue.explanation = "N/A"

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(
                project,
                "Generating SAFE explanation for ${issue.type}…",
                true,
            ) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Calling LLM…"
                    val response = LlmClient.sendRequest(issue, project, level)
                    issue.explanation = response ?: "N/A"

                    // Re-publish so the explanation panel re-renders with the
                    // freshly returned HTML instead of staying on the loading
                    // placeholder forever.
                    ApplicationManager.getApplication().invokeLater {
                        project.messageBus
                            .syncPublisher(ExplanationNotifier.SHOW_EXPLANATION_TOPIC)
                            .showExplanation(issue)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    issue.explanation = "Error: ${error.message ?: error.javaClass.simpleName}"
                    ApplicationManager.getApplication().invokeLater {
                        project.messageBus
                            .syncPublisher(ExplanationNotifier.SHOW_EXPLANATION_TOPIC)
                            .showExplanation(issue)
                    }
                }
            }
        )
    }

    fun explainAllResults(project: Project) {

        ApplicationManager.getApplication().executeOnPooledThread {

            results.issues.forEach { issue ->
                listOf(
                    "Beginner", "Intermediate", "Advanced"
                ).forEach { level ->

                    // This populates the cache for each level
                    LlmClient.sendRequest(issue, project, level)
                }
            }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val node = getSelectedNode() ?: return
        val popup = JPopupMenu()

        when (val userObject = node.userObject) {
            is Issue -> {
                val showInEditorItem = JMenuItem(
                    PluginBundle.lazy("safe.ui.issues.OPEN_IN_EDITOR_OPTION").get()
                )
                val showExplanationItem = JMenuItem(
                    PluginBundle.lazy("safe.ui.issues.SHOW_EXPLANATIONS_OPTION").get()
                )

                showInEditorItem.addActionListener {
                    val messageBus = project.messageBus
                    val publisher: DataflowNotifier =
                        messageBus.syncPublisher(DataflowNotifier.SHOW_EDITOR_TOPIC)
                    publisher.showEditor(userObject)
                }
                showExplanationItem.addActionListener {
                    currentIssue = userObject
                    val cached = !(userObject.explanation.isNullOrBlank()
                        || userObject.explanation == "N/A")
                    de.fraunhofer.iem.safe.blue.study.TelemetryRecorder.getInstance(project).record(
                        event = if (cached) "re_explain.invoked" else "explain.invoked",
                        findingId = telemetryIdOf(userObject),
                        data = mapOf("source" to "context_menu", "force" to cached),
                    )
                    // Kick off the LLM call (status-bar progress, panel auto-updates
                    // when the response arrives). The immediate showExplanation
                    // publish below makes the panel render the loading placeholder
                    // until then.
                    explainResult(project, userObject)

                    val messageBus = project.messageBus
                    val publisher: ExplanationNotifier =
                        messageBus.syncPublisher(ExplanationNotifier.SHOW_EXPLANATION_TOPIC)
                    publisher.showExplanation(userObject)
                }

                popup.add(showInEditorItem)
                popup.add(showExplanationItem)
            }

            is IssueLocation -> {
                val parentNode = node.parent as? DefaultMutableTreeNode ?: return
                val issue = parentNode.userObject as? Issue ?: return

                val jumpToSourceItem = JMenuItem(
                    PluginBundle.lazy("safe.ui.issues.JUMP_TO_SOURCE_OPTION").get(),
                    AllIcons.Actions.EditSource
                )
                jumpToSourceItem.addActionListener {
                    val relativePath = userObject.fileName ?: null
                    val absolutePath = "${project.basePath}/$relativePath"
                    val vFile = LocalFileSystem.getInstance()
                        .findFileByIoFile(File(absolutePath))
                    if (vFile != null) {
                        val descriptor = OpenFileDescriptor(
                            project,
                            vFile,
                            userObject.startLine - 1,
                            -1
                        )
                        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                    }
                }
                popup.add(jumpToSourceItem)
            }
        }

        if (popup.componentCount > 0) {
            popup.show(e.component, e.x, e.y)
        }
    }

    private fun getSelectedNode(): DefaultMutableTreeNode? {
        return lastSelectedPathComponent as? DefaultMutableTreeNode
    }
}