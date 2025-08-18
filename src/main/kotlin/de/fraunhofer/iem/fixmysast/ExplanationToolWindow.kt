package de.fraunhofer.iem.fixmysast

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import de.fraunhofer.iem.fixmysast.ui.panel.ExplanationPanel
import de.fraunhofer.iem.fixmysast.ui.panel.ResultPanel
import de.fraunhofer.iem.fixmysast.ui.ResultsTree
import de.fraunhofer.iem.fixmysast.ui.panel.DataFlowPanel
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingConstants

/**
 * Entry method to the FixMySast intellIJ plugin.
 * The main layout of FixMySast plugin is defined in this class.
 *
 * ✨To infinity... and beyond...✨
 *             - Buzz Lightyear
 *             - Toy story
 */
class ExplanationToolWindow : ToolWindowFactory {

    companion object {
        var resultsTree: ResultsTree? = null
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        //Toolbar action panel
        val toolPanel = JPanel(BorderLayout())

        val actions = ActionManager.getInstance().getAction("FixMySast.Actions") as DefaultActionGroup
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar("FixMySast.Actions", actions, true)
        actionToolbar.targetComponent = toolPanel

        if (toolWindow.anchor.isHorizontal) {
            actionToolbar.orientation = SwingConstants.VERTICAL
            toolPanel.add(actionToolbar.component, BorderLayout.LINE_START)
        } else {
            actionToolbar.orientation = SwingConstants.HORIZONTAL
            toolPanel.add(actionToolbar.component, BorderLayout.PAGE_START)
        }

        if (resultsTree == null) {
            resultsTree = ResultsTree(project)
        }

        val splitPane = JBSplitter(false, 0.4f).apply {
            firstComponent = JBScrollPane(resultsTree)

            val tabs = JTabbedPane()
            tabs.add(
                ResultPanel(project),
                PluginBundle.lazy("fixmysast.ui.tab.result").get()
            )
            tabs.add(
                ExplanationPanel(project),
                PluginBundle.lazy("fixmysast.ui.tab.explanation").get()
            )
            tabs.add(
                DataFlowPanel(project),
                PluginBundle.lazy("fixmysast.ui.tab.dataflow").get()
            )

            secondComponent = tabs
        }

        toolPanel.add(splitPane, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(toolPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}