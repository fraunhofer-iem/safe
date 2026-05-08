package de.fraunhofer.iem.safe.actions

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import de.fraunhofer.iem.safe.comm.ExpertiseLevelNotifier

/**
 * Toolbar action that lets the user pick an expertise level. Originally opened a
 * `JOptionPane` with three radio buttons; refactored into an [ActionGroup] with
 * `isPopup = true` so the three options drop directly from the toolbar button —
 * one click to open, one click to choose. The active level is rendered with a
 * checkmark via [Toggleable].
 *
 * The persisted property keys, the "Regenerating the explanation" notification,
 * and the [ExpertiseLevelNotifier.CHANGE_LEVEL_TOPIC] republish are unchanged so
 * downstream listeners (`ExplanationPanel`, `ResultsTree`, …) keep working.
 */
class LevelSliderAction : ActionGroup() {

    init {
        isPopup = true
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = LEVELS
        .map { SetLevelAction(it) }
        .toTypedArray()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        private val LEVELS = listOf("Beginner", "Intermediate", "Advanced")
        const val EXPERTISE_KEY = "de.fraunhofer.iem.safe.expertiseValue"
        const val PREV_EXPERTISE_KEY = "de.fraunhofer.iem.safe.prevExpertiseValue"
    }
}

/**
 * One option in the popup. Selecting the level that is already active is a no-op
 * (mirrors the original dialog flow, where re-confirming the same selection
 * skipped the regeneration notification).
 */
private class SetLevelAction(private val level: String) :
    AnAction(level), Toggleable {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val props = PropertiesComponent.getInstance(project)
        val previousLevel = props.getValue(LevelSliderAction.EXPERTISE_KEY)
        if (previousLevel == level) return

        props.setValue(LevelSliderAction.PREV_EXPERTISE_KEY, previousLevel)
        props.setValue(LevelSliderAction.EXPERTISE_KEY, level)

        Notifications.Bus.notify(
            Notification(
                "FixMySAST",
                "FixMySAST Update",
                "Regenerating the explanation. Please wait.",
                NotificationType.INFORMATION,
            ),
            project,
        )
        project.messageBus
            .syncPublisher(ExpertiseLevelNotifier.CHANGE_LEVEL_TOPIC)
            .changeLevel(level)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val current = project?.let {
            PropertiesComponent.getInstance(it).getValue(LevelSliderAction.EXPERTISE_KEY)
        }
        Toggleable.setSelected(e.presentation, current == level)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
