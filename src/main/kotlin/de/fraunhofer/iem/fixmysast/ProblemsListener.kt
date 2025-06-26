package de.fraunhofer.iem.fixmysast

import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsListener


class ProblemsListener : ProblemsListener {

    override fun problemAppeared(problem: Problem) {
        println("appeared: "+problem.text)
    }

    override fun problemDisappeared(problem: Problem) {
        println("disappeared: "+problem.text)

    }

    override fun problemUpdated(problem: Problem) {
        println("update: "+problem.text)

    }
}
