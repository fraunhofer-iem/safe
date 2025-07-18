package de.fraunhofer.iem.fixmysast.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.fixmysast.PluginBundle
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
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel


class ResultsTree(project: Project) : Tree() {

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
            explainResults(project)
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


    private fun explainResults(project: Project) {

        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher)

        ApplicationManager.getApplication().executeOnPooledThread {
            results.issues.forEachIndexed { index, issue ->

                issue.explanation = LlmClient.getExplanation(issue, project).toString()
            }
        }
    }
}