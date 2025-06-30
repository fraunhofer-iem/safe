package de.fraunhofer.iem.fixmysast.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class FixMySASTAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {

        val project = e.project ?: return
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("MyToolWindow")
        toolWindow?.show()
    }

}