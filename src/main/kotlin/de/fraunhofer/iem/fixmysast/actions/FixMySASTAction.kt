package de.fraunhofer.iem.fixmysast.actions

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import de.fraunhofer.iem.fixmysast.comm.ParseFileNotifier
import de.fraunhofer.iem.fixmysast.sast.SarifParser

class FixMySASTAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {

        if (PropertiesComponent.getInstance(e.project!!)
                .isValueSet("de.fraunhofer.iem.fixmysast.actions.SastFile")
        ) {
            loadSarifFile(
                e.project!!,
                PropertiesComponent.getInstance(e.project!!)
                    .getValue("de.fraunhofer.iem.fixmysast.actions.SastFile")!!
            )
        } else {

            //Call import file action
            val action = ActionManager.getInstance().getAction("FixMySAST.Import")
            action.actionPerformed(
                AnActionEvent.createFromAnAction(
                    this,
                    null,
                    "", DataContext.EMPTY_CONTEXT
                )
            )
        }
    }

    fun loadSarifFile(project: Project, filePath: String) {

        // Load SARIF results from given path
        val parsedResult = SarifParser.parse(project, filePath)
        val publisher: ParseFileNotifier =
            project.messageBus.syncPublisher(ParseFileNotifier.PARSE_SARIF_FILE)
        publisher.getSastResults(parsedResult)
    }
}