package de.fraunhofer.iem.safe.problems

import com.intellij.analysis.problemsView.ProblemsProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class ResultsProblemProvider(override val project: Project) : ProblemsProvider {

    override fun dispose() {
        // empty override to fix:
        // "references an unresolved class com.intellij.analysis.problemsView.ProblemsProvider.DefaultImpls"
    }
}