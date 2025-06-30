package de.fraunhofer.iem.fixmysast.actions
import com.intellij.openapi.actionSystem.*
import de.fraunhofer.iem.fixmysast.sast.ExpertiseLevel
import de.fraunhofer.iem.fixmysast.sast.LevelStateService

//One radio-button for a single level
class LevelToggleAction(private val level: ExpertiseLevel) : ToggleAction(level.label) {

    override fun isSelected(e: AnActionEvent): Boolean =
        LevelStateService.get().current == level

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) LevelStateService.get().current = level
    }
}

// Container holds 3 LevelToggleAction items
class LevelActionGroup : DefaultActionGroup() {
    init {
        isPopup = true
        templatePresentation.text = "Level"
        add(LevelToggleAction(ExpertiseLevel.BEGINNER))
        add(LevelToggleAction(ExpertiseLevel.INTERMEDIATE))
        add(LevelToggleAction(ExpertiseLevel.ADVANCED))
    }
}