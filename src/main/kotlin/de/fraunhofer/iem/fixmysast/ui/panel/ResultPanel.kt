package de.fraunhofer.iem.fixmysast.ui.panel

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.messages.MessageBus
import com.intellij.util.ui.JBUI
import de.fraunhofer.iem.fixmysast.comm.ExplanationNotifier
import de.fraunhofer.iem.fixmysast.sast.Issue
import java.awt.BorderLayout
import javax.swing.JPanel

class ResultPanel( val project: Project) : JPanel()  {

    val sastResult = JBLabel()
    val bus: MessageBus = project.messageBus

    init {
        layout = BorderLayout()

        sastResult.setBorder(JBUI.Borders.empty(10))
        sastResult.isAllowAutoWrapping = true
        add(sastResult, BorderLayout.NORTH)

        //Subscribe to the response topic to get response
        bus.connect().subscribe(
            ExplanationNotifier.SHOW_EXPLANATION_TOPIC,
            object : ExplanationNotifier {

                override fun showExplanation(issue: Issue) {
                    sastResult.text = "<html><b>" + issue.type + "</b> <br>" + issue.message + "</html>"
                }
            })
    }
}