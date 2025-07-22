package de.fraunhofer.iem.fixmysast.actions
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import de.fraunhofer.iem.fixmysast.ExplanationToolWindow
import de.fraunhofer.iem.fixmysast.llm.LevelStateService
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JSlider

class LevelSliderAction : AnAction("Set Expertise Level") {
    override fun actionPerformed(e: AnActionEvent){

        val project = e.project ?: return

        val props = PropertiesComponent.getInstance(project)

        val previousLevel = props.getValue("de.fraunhofer.iem.fixmysast.expertiseValue")?.toIntOrNull()

        println("Previous Level: $previousLevel")

        val slider = JSlider(0, 10, previousLevel ?: 5)

        println("Current slider: $slider")

        slider.majorTickSpacing = 1
        slider.paintTicks = true
        slider.paintLabels = true

        val result = JOptionPane.showConfirmDialog(
            null,
            slider,
            "Select Your Expertise Level",
            JOptionPane.OK_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )

        if (result == JOptionPane.OK_OPTION) {
            val newLevel = slider.value
            println("New slider level is $newLevel")
            props.setValue("de.fraunhofer.iem.fixmysast.expertiseValue", newLevel.toString())
            val curr = props.getValue("de.fraunhofer.iem.fixmysast.expertiseValue")
            println("Current slider value inside properties is $curr")
            if (newLevel != previousLevel) {
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