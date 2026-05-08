//package de.fraunhofer.iem.fixmysast.actions
//import com.intellij.notification.Notification
//import com.intellij.notification.NotificationType
//import com.intellij.notification.Notifications
//import com.intellij.openapi.actionSystem.*
//import de.fraunhofer.iem.fixmysast.ExplanationToolWindow
//import de.fraunhofer.iem.fixmysast.llm.ExpertiseLevel
//import de.fraunhofer.iem.fixmysast.llm.LevelStateService
//
////One radio-button for a single level
//class LevelToggleAction(private val level: ExpertiseLevel) : ToggleAction(level.label) {
//
//    override fun isSelected(e: AnActionEvent): Boolean =
//        LevelStateService.get().current == level
//
//    override fun setSelected(e: AnActionEvent, state: Boolean) {
//        if (state) {
//            LevelStateService.get().current = level
//            if (ExplanationToolWindow.resultsTree != null) {
//                Notifications.Bus.notify(
//                    Notification(
//                        "Nofication",
//                        "Re-generating",
//                        "Regenerating the explanation. Please wait.",
//                        NotificationType.INFORMATION
//                    )
//                )
//                ExplanationToolWindow.resultsTree!!.refreshTree()
//            }
//        }
//    }
//}
