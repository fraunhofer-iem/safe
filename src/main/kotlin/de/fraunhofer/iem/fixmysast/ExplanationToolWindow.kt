package de.fraunhofer.iem.fixmysast

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import de.fraunhofer.iem.fixmysast.ui.ExplanationPanel
import de.fraunhofer.iem.fixmysast.ui.VulnerabilityList
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

class ExplanationToolWindow : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        //Toolbar action panel
        val toolPanel = JPanel(BorderLayout())

        val actions = ActionManager.getInstance().getAction("FixMySast.Actions") as DefaultActionGroup
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar("ActionToolbar", actions, true)
        actionToolbar.targetComponent = toolPanel

        if (toolWindow.anchor.isHorizontal) {
            actionToolbar.orientation = SwingConstants.VERTICAL
            toolPanel.add(actionToolbar.component, BorderLayout.LINE_START)
        } else {
            actionToolbar.orientation = SwingConstants.HORIZONTAL
            toolPanel.add(actionToolbar.component, BorderLayout.PAGE_START)
        }

        val leftPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val listScrollPane = JBScrollPane(VulnerabilityList(project))
        leftPanel.add(listScrollPane)

        val leftScrollPane = JBScrollPane(leftPanel)
        val rightScrollPane = JBScrollPane(ExplanationPanel(project))

        val splitPane = JBSplitter(false, 0.2f).apply {
            firstComponent = leftScrollPane
            secondComponent = rightScrollPane
        }

        toolPanel.add(splitPane, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(toolPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}