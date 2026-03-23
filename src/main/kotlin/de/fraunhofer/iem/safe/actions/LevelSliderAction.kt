package de.fraunhofer.iem.safe.actions
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import de.fraunhofer.iem.safe.comm.ExpertiseLevelNotifier
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton

class LevelSliderAction : AnAction("Set Expertise Level") {
    override fun actionPerformed(e: AnActionEvent){

        val project = e.project ?: return

        val props = PropertiesComponent.getInstance(project)

        val previousLevel = props.getValue("de.fraunhofer.iem.safe.expertiseValue")
        props.setValue("de.fraunhofer.iem.safe.prevExpertiseValue", previousLevel)

        val lowButton = JRadioButton("Beginner")
        val medButton = JRadioButton("Intermediate")
        val highButton = JRadioButton("Advanced")

        if(previousLevel.contentEquals("Beginner"))
            lowButton.isSelected = true
        else if(previousLevel.contentEquals("Intermediate"))
            medButton.isSelected = true
        else if(previousLevel.contentEquals("Advanced"))
            highButton.isSelected = true

        val levelGroup = ButtonGroup()
        levelGroup.add(lowButton)
        levelGroup.add(medButton)
        levelGroup.add(highButton)

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
            println("New slider level is $newCategory")
            props.setValue("de.fraunhofer.iem.safe.expertiseValue", newCategory)
            val curr = props.getValue("de.fraunhofer.iem.safe.expertiseValue")
            println("Current slider value inside properties is $curr")
            if (newCategory != previousLevel) {
                Notifications.Bus.notify(
                    Notification(
                    "FixMySAST",
                    "FixMySAST Update",
                    "Regenerating the explanation. Please wait.",
                    NotificationType.INFORMATION
                ),
                    project)
                val bus = project.messageBus
                val publisher = bus.syncPublisher(ExpertiseLevelNotifier.CHANGE_LEVEL_TOPIC)
                publisher.changeLevel(curr.toString())
            }
        }
    }
}