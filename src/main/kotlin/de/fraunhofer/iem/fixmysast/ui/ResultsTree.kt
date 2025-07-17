package de.fraunhofer.iem.fixmysast.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.fixmysast.comm.DataflowNotifier
import de.fraunhofer.iem.fixmysast.comm.ExplanationNotifier
import de.fraunhofer.iem.fixmysast.comm.ParseFileNotifier
import de.fraunhofer.iem.fixmysast.llm.LlmClient
import de.fraunhofer.iem.fixmysast.sast.Issue
import de.fraunhofer.iem.fixmysast.sast.JsonParser
import de.fraunhofer.iem.fixmysast.sast.Results
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
    var resultsModel: DefaultTreeModel
    var rootNode: DefaultMutableTreeNode

    init {

        rootNode = DefaultMutableTreeNode("Vulnerabilities")
        emptyText.setText("No SAST file selected.")

        resultsModel = DefaultTreeModel(rootNode)
        model = resultsModel
        cellRenderer = ResultsTreeRenderer()

        //Load last file
        if (PropertiesComponent.getInstance(project)
                .isValueSet("de.fraunhofer.iem.fixmysast.actions.SastFile")
        ) {

            // Load SARIF results from given path
            //val parsedResult = SarifParser.parse(
          val parsedResult = JsonParser.parse(
                project, PropertiesComponent.getInstance(project)
                    .getValue("de.fraunhofer.iem.fixmysast.actions.SastFile")!!
            )
            if(parsedResult!=null){
                updateTree(parsedResult)
                explainResults()
            }
        }

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
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
        bus.connect().subscribe(ParseFileNotifier.PARSE_SARIF_FILE, object : ParseFileNotifier {
            override fun getSastResults(results: Results) {

                updateTree(results)
                explainResults()
            }
        })
    }

    fun updateTree(results: Results) {

        this.results = results

        val resultsTreeNode = DefaultMutableTreeNode(results.filePath)

        for (issue in results.issues) {
            resultsTreeNode.add(DefaultMutableTreeNode(issue))
        }
        rootNode.add(resultsTreeNode)
    }


    private fun explainResults() {

        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher)

        ApplicationManager.getApplication().executeOnPooledThread {
            results.issues.forEach { issue ->

                issue.explanation  = LlmClient.getExplanation(issue).toString()
                println("Response for: "+issue.type)
        }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val node = getSelectedNode() ?: return
        val issue = node.userObject as? Issue ?: return

        val popup = JPopupMenu()
        val showInEditorItem = JMenuItem("Show in Editor")
        val showExplanationItem = JMenuItem("Get LLM Explanation")

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