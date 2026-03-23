package de.fraunhofer.iem.safe.actions
import com.intellij.openapi.actionSystem.*
import de.fraunhofer.iem.safe.llm.ExpertiseLevel

//One radio-button for a single level
class LevelToggleAction(private val level: ExpertiseLevel) : ToggleAction(level.toString()) {
    /*
    override fun isSelected(e: AnActionEvent): Boolean =
        LevelStateService.get().current == level

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) {
            LevelStateService.get().current = level
            if (ExplanationToolWindow.resultsTree != null) {
                Notifications.Bus.notify(
                    Notification(
                        "Nofication",
                        "Re-generating",
                        "Regenerating the explanation. Please wait.",
                        NotificationType.INFORMATION
                    )
                )
            }
        }
    }*/
    override fun isSelected(p0: AnActionEvent): Boolean {
        TODO("Not yet implemented")
    }

    override fun setSelected(p0: AnActionEvent, p1: Boolean) {
        TODO("Not yet implemented")
    }
}
