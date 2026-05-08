package de.fraunhofer.iem.safe.blue.ui.panel

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.MessageBus
import com.intellij.util.ui.JBUI
import de.fraunhofer.iem.safe.blue.comm.ResultNotifier
import de.fraunhofer.iem.safe.blue.sast.Issue
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

class ResultPanel(val project: Project) : JPanel() {

    val sastResult = JBLabel()
    val bus: MessageBus = project.messageBus

    init {
        layout = BorderLayout()

        sastResult.setBorder(JBUI.Borders.empty(10))
        sastResult.isAllowAutoWrapping = true
        // Anchor text to the top of the viewport so short messages don't get
        // vertically centered now that the label lives inside a scroll pane.
        sastResult.verticalAlignment = SwingConstants.TOP

        val scroll = JBScrollPane(sastResult).apply {
            border = JBUI.Borders.empty()
            // Disable the horizontal scrollbar so JBLabel's auto-wrapping kicks in
            // at the viewport width instead of letting the label run off the edge.
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        // BorderLayout.CENTER fills the available area without letting the label's
        // preferred size dictate the panel's size — previously the label sat in
        // NORTH, which made the panel's preferred / minimum width grow with the
        // text and pushed the JBSplitter's divider into the tree on the left.
        add(scroll, BorderLayout.CENTER)

        //Subscribe to the response topic to get response
        bus.connect().subscribe(
            ResultNotifier.SHOW_RESULT_TOPIC,
            object : ResultNotifier {

                override fun showResult(issue: Issue) {
                    val output = StringBuilder()
                    output.append("<html><b>${issue.type}:</b> ${issue.message}<br>")
                    output.append("<br><b>File name:</b> ${issue.location.fileName}<br>")

                    output.append("<br><b>Line Number:</b> ${issue.location.startLine}<br>")

                    output.append("<br><b>Code Snippet:</b> ${issue.location.codeSnippet}<br>")

                    output.append("</html>")

                    sastResult.text = output.toString()
                }
            })
    }

    /**
     * The JBSplitter that hosts this panel respects child minimum sizes when placing
     * its divider. JBLabel reports its full HTML preferred size as its minimum, so
     * long messages used to push the splitter all the way left and cover the tree.
     * Returning a small minimum tells the splitter to stick to its configured
     * proportion regardless of how much text is on screen.
     */
    override fun getMinimumSize(): Dimension = Dimension(0, 0)
}
