package de.fraunhofer.iem.safe.blue

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import de.fraunhofer.iem.safe.blue.ui.panel.ExplanationPanel
import de.fraunhofer.iem.safe.blue.ui.panel.ResultPanel
import de.fraunhofer.iem.safe.blue.ui.ResultsTree
import de.fraunhofer.iem.safe.blue.ui.panel.DataFlowPanel
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingConstants

/**
 * Entry method to the SAFE-Blue IntelliJ plugin.
 * The main layout of SAFE-Blue plugin is defined in this class.
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

        val actions = ActionManager.getInstance().getAction("Safe.Actions.Blue") as DefaultActionGroup
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar("Safe.Actions.Blue", actions, true)
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
                PluginBundle.lazy("safe.ui.tab.result").get()
            )
            tabs.add(
                ExplanationPanel(project),
                PluginBundle.lazy("safe.ui.tab.explanation").get()
            )
            tabs.add(
                DataFlowPanel(project),
                PluginBundle.lazy("safe.ui.tab.dataflow").get()
            )

            // Tab-switch telemetry — emits `panel.opened` / `panel.closed` /
            // `panel.dwell` keyed by tab title (e.g. "Result" / "Explanation" /
            // "Data Flow"). One pair of bracket events per visit so analysis
            // can see how long participants spent on each pane.
            var lastTabIndex = tabs.selectedIndex
            var lastTabEnteredAt = System.currentTimeMillis()
            val recorder = de.fraunhofer.iem.safe.blue.study.TelemetryRecorder.getInstance(project)
            // Initial open of whichever tab is selected on first paint.
            recorder.record(
                event = "panel.opened",
                data = mapOf("panel" to tabs.getTitleAt(lastTabIndex)),
            )
            tabs.addChangeListener {
                val now = System.currentTimeMillis()
                val outgoingTitle = tabs.getTitleAt(lastTabIndex)
                recorder.record(
                    event = "panel.closed",
                    data = mapOf("panel" to outgoingTitle),
                )
                recorder.record(
                    event = "panel.dwell",
                    data = mapOf(
                        "panel" to outgoingTitle,
                        "ms" to (now - lastTabEnteredAt).coerceAtLeast(0L),
                    ),
                )
                lastTabIndex = tabs.selectedIndex
                lastTabEnteredAt = now
                recorder.record(
                    event = "panel.opened",
                    data = mapOf("panel" to tabs.getTitleAt(lastTabIndex)),
                )
            }

            secondComponent = tabs
        }

        toolPanel.add(splitPane, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(toolPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}