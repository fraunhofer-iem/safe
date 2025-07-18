package de.fraunhofer.iem.fixmysast.actions
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import de.fraunhofer.iem.fixmysast.llm.LevelStateService
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JSlider

//One radio-button for a single level
//class LevelToggleAction(private val level: ExpertiseLevel) : ToggleAction(level.label) {
//
//    override fun isSelected(e: AnActionEvent): Boolean =
//        LevelStateService.get().current == level
//
//    override fun setSelected(e: AnActionEvent, state: Boolean) {
//        if (state) LevelStateService.get().current = level
//    }
//}
//
//// Container holds 3 LevelToggleAction items
//class LevelActionGroup : DefaultActionGroup() {
//    init {
//        isPopup = true
//        templatePresentation.text = "Level"
//        add(LevelToggleAction(ExpertiseLevel.BEGINNER))
//        add(LevelToggleAction(ExpertiseLevel.INTERMEDIATE))
//        add(LevelToggleAction(ExpertiseLevel.ADVANCED))
//    }
//}


class LevelSliderAction : AnAction("Set Expertise Level") {
    override fun actionPerformed(e: AnActionEvent){

        val project = e.project ?: return

        val slider = JSlider(0, 10, LevelStateService.get().current)
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
            LevelStateService.get().current= slider.value

            PropertiesComponent.getInstance(project)
                .setValue("de.fraunhofer.iem.fixmysast.expertiseValue", slider.value.toString())
        }

    }
}