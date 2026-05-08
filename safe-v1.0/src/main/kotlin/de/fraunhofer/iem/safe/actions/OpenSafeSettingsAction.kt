package de.fraunhofer.iem.safe.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import de.fraunhofer.iem.safe.settings.SafeSettingsConfigurable

/**
 * Toolbar action that opens the SAFE settings page (Settings | Tools | SAFE) in
 * one click. The same configurable is also reachable through the regular IDE
 * settings dialog; this is just a shortcut from the SAFE tool window.
 */
class OpenSafeSettingsAction : AnAction(
    "SAFE Settings",
    "Configure the SAFE plugin's LLM platform, endpoint, model, and API key",
    AllIcons.General.Settings,
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            e.project,
            SafeSettingsConfigurable::class.java,
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
