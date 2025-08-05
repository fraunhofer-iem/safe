package de.fraunhofer.iem.fixmysast.actions
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import de.fraunhofer.iem.fixmysast.ExplanationToolWindow
import de.fraunhofer.iem.fixmysast.llm.LevelStateService
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSlider

class LevelSliderAction : AnAction("Set Expertise Level") {
    override fun actionPerformed(e: AnActionEvent){

        val project = e.project ?: return

        val props = PropertiesComponent.getInstance(project)

        val previousLevel = props.getValue("de.fraunhofer.iem.fixmysast.expertiseValue")

        val lowButton = JRadioButton("Beginner")
        val medButton = JRadioButton("Intermediate")
        val highButton = JRadioButton("Advanced")

        val group = ButtonGroup().apply {
            add(lowButton)
            add(medButton)
            add(highButton)
        }

        val panel = JPanel().apply{
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(lowButton)
            add(medButton)
            add(highButton)
        }
        val result = JOptionPane.showConfirmDialog(
            null,
            panel,
            "Select Your Expertise Level",
            JOptionPane.OK_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )

        if (result == JOptionPane.OK_OPTION) {

            val selected = when {
                lowButton.isSelected -> "Beginner"
                medButton.isSelected -> "Intermediate"
                highButton.isSelected -> "Advanced"
                else -> { "Intermediate" }
            }

            val newCategory = selected
            //println("New slider level is $newLevel")
            props.setValue("de.fraunhofer.iem.fixmysast.expertiseValue", newCategory)
            val curr = props.getValue("de.fraunhofer.iem.fixmysast.expertiseValue")
            println("Current slider value inside properties is $curr")
            if (newCategory != previousLevel) {
                Notifications.Bus.notify(
                    Notification(
                    "FixMySAST",
                    "Re-generating",
                    "Regenerating the explanation. Please wait.",
                    NotificationType.INFORMATION
                ),
                    project)
                ExplanationToolWindow.resultsTree?.refreshTree(project)
            }
        }
    }
}