package de.fraunhofer.iem.fixmysast.actions

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import de.fraunhofer.iem.fixmysast.comm.ParseFileNotifier
import de.fraunhofer.iem.fixmysast.sast.SASTParser
import java.io.File

class FixMySASTAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        //TODO: This file name must be taken dynamically and not hardcoded.
        val SARIF_FILE = "Benchmark_1.2-Semgrep-v1.123.0_Edited.sarif"

        //Save file location
        PropertiesComponent.getInstance(e.project!!)
            .setValue("de.fraunhofer.iem.fixmysast.actions.SastFile",
                e.project!!.basePath + File.separator + "results" + File.separator + SARIF_FILE)


        if (PropertiesComponent.getInstance(e.project!!)
                .isValueSet("de.fraunhofer.iem.fixmysast.actions.SastFile")
        ) {
            loadSarifFile(
                e.project!!,
                PropertiesComponent.getInstance(e.project!!)
                    .getValue("de.fraunhofer.iem.fixmysast.actions.SastFile",
                        e.project!!.basePath + File.separator + "results" + File.separator + SARIF_FILE)
            )
        } else {

            //Call import file action

        }
    }

    fun loadSarifFile(project: Project, filePath: String) {

        // Load SARIF results from given path
        val parsedResult = SASTParser.parseSarifFromProject(project!!, filePath)
        val publisher: ParseFileNotifier =
            project!!.messageBus.syncPublisher(ParseFileNotifier.PARSE_SARIF_FILE)
        publisher.getSastResults(parsedResult)
    }
}