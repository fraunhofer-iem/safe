package de.fraunhofer.iem.safe.ui.panel

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.messages.MessageBus
import com.intellij.util.ui.JBUI
import de.fraunhofer.iem.safe.comm.ResultNotifier
import de.fraunhofer.iem.safe.sast.Issue
import java.awt.BorderLayout
import javax.swing.JPanel

class ResultPanel(val project: Project) : JPanel() {

    val sastResult = JBLabel()
    val bus: MessageBus = project.messageBus

    init {
        layout = BorderLayout()

        sastResult.setBorder(JBUI.Borders.empty(10))
        sastResult.isAllowAutoWrapping = true
        add(sastResult, BorderLayout.NORTH)

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
}