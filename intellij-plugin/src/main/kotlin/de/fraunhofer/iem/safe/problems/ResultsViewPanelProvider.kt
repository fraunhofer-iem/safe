package de.fraunhofer.iem.safe.problems

import com.intellij.analysis.problemsView.toolWindow.ProblemsViewPanelProvider
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewState
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewTab
import com.intellij.openapi.project.Project

class ResultsViewPanelProvider(private val project: Project) : ProblemsViewPanelProvider {

    override fun create(): ProblemsViewTab = ResultsViewPanel(
        project,
        ProblemsViewState.getInstance(project)
    )
}