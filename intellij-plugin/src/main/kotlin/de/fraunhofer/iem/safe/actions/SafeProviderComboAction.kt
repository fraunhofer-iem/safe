package de.fraunhofer.iem.safe.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.application.ApplicationManager
import de.fraunhofer.iem.safe.llm.ProviderKind
import de.fraunhofer.iem.safe.llm.SafeLlmSettings
import de.fraunhofer.iem.safe.llm.SafeProviderChangeListener

/**
 * Toolbar dropdown that switches the active LLM/agent provider. As an [ActionGroup] with
 * [isPopup] = true, the toolbar renders it with the standard small-triangle indicator in
 * the bottom-right of the button — no manual icon layering required (that double-stamps
 * the indicator).
 *
 * Clicking the button opens the provider list; the active provider gets a checkmark
 * (via [Toggleable]). Picking one updates [SafeLlmSettings] and broadcasts
 * [SafeProviderChangeListener.TOPIC] so the SAFE tool window refreshes its tree to show
 * that provider's explanations.
 */
class SafeProviderComboAction : ActionGroup() {

    init {
        isPopup = true
        templatePresentation.icon = AllIcons.Actions.Lightning
    }

    override fun update(e: AnActionEvent) {
        val active = SafeLlmSettings.getInstance().providerKind
        e.presentation.description = "Active SAFE provider: ${active.displayName}"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        ProviderKind.entries.map { SetProviderAction(it) }.toTypedArray()
}

/**
 * Selects [target] as the active provider. [Toggleable] makes the popup paint a
 * checkmark next to the entry currently in use.
 */
private class SetProviderAction(private val target: ProviderKind) :
    AnAction(target.displayName), Toggleable {

    override fun actionPerformed(e: AnActionEvent) {
        val settings = SafeLlmSettings.getInstance()
        if (settings.providerKind == target) return
        settings.providerKind = target
        ApplicationManager.getApplication().messageBus
            .syncPublisher(SafeProviderChangeListener.TOPIC)
            .providerChanged(target.id)
    }

    override fun update(e: AnActionEvent) {
        Toggleable.setSelected(e.presentation, SafeLlmSettings.getInstance().providerKind == target)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
