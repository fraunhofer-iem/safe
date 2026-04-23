package de.fraunhofer.iem.safe

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import de.fraunhofer.iem.safe.ui.ExplainPanel

class ExplainerToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ExplainPanel(project)

        val expandAction = object : AnAction("Expand All", "Expand all nodes", AllIcons.Actions.Expandall) {
            override fun actionPerformed(e: AnActionEvent) = panel.expandAllNodes()
        }

        val collapseAction = object : AnAction("Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall) {
            override fun actionPerformed(e: AnActionEvent) = panel.collapseAllNodes()
        }

        toolWindow.setTitleActions(listOf(expandAction, collapseAction))

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}