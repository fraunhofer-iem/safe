package de.fraunhofer.iem.safe.ui

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
import de.fraunhofer.iem.safe.PluginBundle
import de.fraunhofer.iem.safe.comm.DataflowNotifier
import de.fraunhofer.iem.safe.comm.ExplanationNotifier
import de.fraunhofer.iem.safe.comm.ParseFileNotifier
import de.fraunhofer.iem.safe.comm.ResultNotifier
import de.fraunhofer.iem.safe.llm.LlmClient
import de.fraunhofer.iem.safe.sast.Issue
import de.fraunhofer.iem.safe.sast.IssueLocation
import de.fraunhofer.iem.safe.sast.JsonParser
import de.fraunhofer.iem.safe.sast.Results
import de.fraunhofer.iem.safe.sast.SarifParser
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
                PluginBundle.lazy("fixmysast.ui.tree.empty").get(),
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            )
        }

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {

                val node = lastSelectedPathComponent as DefaultMutableTreeNode?

                val messageBus = project.getMessageBus()

                if (node != null && node.userObject is Issue) {

                    val issue = node.userObject as Issue
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
                    PluginBundle.lazy("fixmysast.ui.issues.OPEN_IN_EDITOR_OPTION").get()
                )
                val showExplanationItem = JMenuItem(
                    PluginBundle.lazy("fixmysast.ui.issues.SHOW_EXPLANATIONS_OPTION").get()
                )

                showInEditorItem.addActionListener {
                    val messageBus = project.messageBus
                    val publisher: DataflowNotifier =
                        messageBus.syncPublisher(DataflowNotifier.SHOW_EDITOR_TOPIC)
                    publisher.showEditor(userObject)
                }
                showExplanationItem.addActionListener {
                    currentIssue = userObject
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
                    PluginBundle.lazy("fixmysast.ui.issues.JUMP_TO_SOURCE_OPTION").get(),
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