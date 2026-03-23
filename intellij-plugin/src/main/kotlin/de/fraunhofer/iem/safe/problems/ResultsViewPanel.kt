package de.fraunhofer.iem.safe.problems

import com.intellij.analysis.problemsView.toolWindow.ProblemsViewPanel
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewState
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewToolWindowUtils
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import de.fraunhofer.iem.safe.PluginBundle
import de.fraunhofer.iem.safe.problems.ResultsManager


private const val FINDINGS_TAB_ID = "de.fraunhofer.iem.safe.problems.tab"

class ResultsViewPanel(
    project: Project, state: ProblemsViewState
) : ProblemsViewPanel(
    project,
    FINDINGS_TAB_ID,
    state,
    PluginBundle.lazy("fixmysast.configuration.title"),
) {

    init {

       // treeModel.root = ResultsRootNode(project, this)

        ResultsManager.Companion.getInstance(project).register {
            invokeLater {
                treeModel.structureChanged(null)
                ProblemsViewToolWindowUtils.selectTab(project, FINDINGS_TAB_ID)
            }
        }
    }
}