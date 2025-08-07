package de.fraunhofer.iem.fixmysast.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.fixmysast.PluginBundle
import de.fraunhofer.iem.fixmysast.comm.DataflowNotifier
import de.fraunhofer.iem.fixmysast.comm.ExplanationNotifier
import de.fraunhofer.iem.fixmysast.comm.ParseFileNotifier
import de.fraunhofer.iem.fixmysast.comm.ResultNotifier
import de.fraunhofer.iem.fixmysast.llm.LlmClient
import de.fraunhofer.iem.fixmysast.sast.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import java.util.concurrent.Executors
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel


class ResultsTree(private val project: Project) : Tree() {

    private lateinit var results: Results
    private var currentIssue: Issue? = null
    val bus: MessageBus = project.messageBus
    var preloadSetting: Boolean = true

    init {

        cellRenderer = ResultsTreeRenderer()

        //Load last file
        if (PropertiesComponent.getInstance(project)
                .isValueSet("de.fraunhofer.iem.fixmysast.file")
        ) {

            // Load SAST results from given path
            addTreeNodes(
                parseFile(
                    PropertiesComponent.getInstance(project)
                        .getValue("de.fraunhofer.iem.fixmysast.file")!!,
                    project
                )
            )

            explainResults(project)
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

                    val resultPanel = messageBus.syncPublisher(ResultNotifier.SHOW_RESULT_TOPIC)
                    resultPanel.showResult(issue)
                } else if (node != null && node.userObject is IssueLocation) {

                    val parent = node.parent as DefaultMutableTreeNode?
                    val issue = parent!!.userObject as Issue

                    /*Notifications.Bus.notify(
                        Notification(
                            "Notification",
                            "Generating Response",
                            "Please wait, response is being generated...",
                            NotificationType.INFORMATION
                        )
                    )*/

                    explainResult(project, issue)
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
                        .setValue("de.fraunhofer.iem.fixmysast.file", sastFile)

                    explainResults(project)
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

            val issueNode = DefaultMutableTreeNode( issue)
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

        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher)

        val level = PropertiesComponent.getInstance(project)
            .getValue("de.fraunhofer.iem.fixmysast.expertiseValue")         // ← persisted default

        //change to only load file when clicked on
        ApplicationManager.getApplication().executeOnPooledThread {

            issue.explanation = LlmClient.getExplanation(
                issue, project,
                level.toString()
            ).toString()

            println(
                "************************************************\n" +
                        issue.type + "\n" +
                        issue.location + "\n" +
                        level.toString() + "\n" +
                        "************************************************" +
                        issue.explanation +"\n" +
                        "************************************************"
            )
        }
        //cellRenderer = ResultsTreeRenderer()
    }

    fun explainResults(project: Project) {

        ApplicationManager.getApplication().executeOnPooledThread {
            println("START: "+ Calendar.getInstance().time)
            results.issues.forEach { issue ->
                listOf("Beginner"
                    , "Intermediate", "Advanced"
                ).forEach { level ->

                    // This populates the cache for each level
                    LlmClient.getExplanation(issue, project, level)
                }
            }
            println("END: "+ Calendar.getInstance().time)
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val node = getSelectedNode() ?: return
        val issue = node.userObject as? Issue ?: return

        val popup = JPopupMenu()
        val showInEditorItem = JMenuItem(PluginBundle.lazy("fixmysast.ui.issues.OPEN_IN_EDITOR_OPTION").get())
        val showExplanationItem = JMenuItem(PluginBundle.lazy("fixmysast.ui.issues.SHOW_EXPLANATIONS_OPTION").get())

        showInEditorItem.addActionListener {
            val messageBus = project.messageBus
            val publisher: DataflowNotifier =
                messageBus.syncPublisher(DataflowNotifier.SHOW_EDITOR_TOPIC)
            publisher.showEditor(issue)
        }
        showExplanationItem.addActionListener {
            val messageBus = project.messageBus
            val publisher: ExplanationNotifier =
                messageBus.syncPublisher(ExplanationNotifier.SHOW_EXPLANATION_TOPIC)
            publisher.showExplanation(issue)
        }

        popup.add(showInEditorItem)
        popup.add(showExplanationItem)
        popup.show(e.component, e.x, e.y)
    }

    private fun getSelectedNode(): DefaultMutableTreeNode? {
        return lastSelectedPathComponent as? DefaultMutableTreeNode
    }
}