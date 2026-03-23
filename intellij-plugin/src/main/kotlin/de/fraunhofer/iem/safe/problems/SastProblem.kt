package de.fraunhofer.iem.safe.problems

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VirtualFile
import de.fraunhofer.iem.safe.problems.ResultsProblemProvider
import de.fraunhofer.iem.safe.problems.model.Finding
import javax.swing.Icon

class SastProblem(
    override val provider: ResultsProblemProvider,
    override val file: VirtualFile,
    private val finding: Finding,
) : FileProblem {

    override val text: String
        get() = finding.description

    override val description: String
        get() = finding.description

    override val group: String
        get() = finding.ruleId

    override val icon: Icon
        get() = AllIcons.Actions.Redo

    override val line: Int
        get() = finding.lineNumber - 1

    override val column: Int
        get() = finding.columnNumber - 1
}