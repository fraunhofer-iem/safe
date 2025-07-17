package de.fraunhofer.iem.fixmysast.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.fixmysast.comm.ExplanationNotifier
import de.fraunhofer.iem.fixmysast.comm.ParseFileNotifier
import de.fraunhofer.iem.fixmysast.llm.LlmClient
import de.fraunhofer.iem.fixmysast.sast.Issue
import de.fraunhofer.iem.fixmysast.sast.Results
import de.fraunhofer.iem.fixmysast.sast.SarifParser
import de.fraunhofer.iem.fixmysast.sast.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode


class ResultsTree(project: Project) : Tree() {

    var isExplained: Boolean = false
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
            //Note: Choose between JsonParser or SASTParser here
            val parsedResult = JsonParser.parse(
                project, PropertiesComponent.getInstance(project)
                    .getValue("de.fraunhofer.iem.fixmysast.actions.SastFile")!!
            )
            updateTree(parsedResult)
            explainResults()
        }

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {

                val node = lastSelectedPathComponent as DefaultMutableTreeNode?

                if (node != null && node.userObject is Issue) {
                    val issue = node.userObject as Issue

                    val messageBus = project.getMessageBus()
                    val publisher: ExplanationNotifier =
                        messageBus.syncPublisher(ExplanationNotifier.SHOW_EXPLANATION_TOPIC)
                    publisher.showExplanation(issue)
                }
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
        rootNode.removeAllChildren()

        val resultsTreeNode = DefaultMutableTreeNode(results.filePath)

        for (issue in results.issues) {
            resultsTreeNode.add(DefaultMutableTreeNode(issue))
        }
        rootNode.add(resultsTreeNode)
        resultsModel.reload()
    }


    private fun explainResults() {

        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher)

        ApplicationManager.getApplication().executeOnPooledThread {
            val fileNode = rootNode.lastChild as? DefaultMutableTreeNode ?: return@executeOnPooledThread
            results.issues.forEachIndexed {index, issue ->

                issue.explanation  = LlmClient.getExplanation(issue).toString()

                val issueNode = fileNode.getChildAt(index) as DefaultMutableTreeNode
                SwingUtilities.invokeLater {
                    resultsModel.nodeChanged(issueNode)
                    this.repaint()
                }
            }
        }
    }
}