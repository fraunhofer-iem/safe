package de.fraunhofer.iem.fixmysast.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.fixmysast.PluginBundle
import de.fraunhofer.iem.fixmysast.comm.DataflowNotifier
import de.fraunhofer.iem.fixmysast.comm.ExplanationNotifier
import de.fraunhofer.iem.fixmysast.comm.ParseFileNotifier
import de.fraunhofer.iem.fixmysast.llm.LlmClient
import de.fraunhofer.iem.fixmysast.sast.Issue
import de.fraunhofer.iem.fixmysast.sast.JsonParser
import de.fraunhofer.iem.fixmysast.sast.Results
import de.fraunhofer.iem.fixmysast.sast.SarifParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel


class ResultsTree(private val project: Project) : Tree() {

    private lateinit var results: Results
    val bus: MessageBus = project.messageBus

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
            explainResults()
        } else {
            model = null
            this.emptyText.setText(
                PluginBundle.lazy("fixmysast.ui.tree.empty").get(),
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            )
        }

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {

//                val node = lastSelectedPathComponent as DefaultMutableTreeNode?
//
//                if (node != null && node.userObject is Issue) {
//                    val issue = node.userObject as Issue
//
//                    val messageBus = project.getMessageBus()
//                    val publisher: ExplanationNotifier =
//                        messageBus.syncPublisher(ExplanationNotifier.SHOW_EXPLANATION_TOPIC)
//                    publisher.showExplanation(issue)
//                }
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

                    explainResults()
                }
            })
    }

    fun refreshTree() {
        explainResults(true)
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
            issueNode.add(DefaultMutableTreeNode(issue.location))
            resultsTreeNode.add(issueNode)
        }
        rootNode.add(resultsTreeNode)
    }


    private fun explainResults(isRegenerate: Boolean = false) {

        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher)

        ApplicationManager.getApplication().executeOnPooledThread {
            results.issues.forEachIndexed { index, issue ->

                issue.explanation = LlmClient.getExplanation(issue).toString()
            }

            if (isRegenerate) {
                Notifications.Bus.notify(
                    Notification(
                        "Nofication",
                        "Re-generating",
                        "Successfully re-generated new responses for all the issues.",
                        NotificationType.INFORMATION
                    )
                )
            }
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