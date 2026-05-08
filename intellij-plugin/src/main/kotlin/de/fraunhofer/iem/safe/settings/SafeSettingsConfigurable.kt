package de.fraunhofer.iem.safe.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import de.fraunhofer.iem.safe.llm.ProviderKind
import de.fraunhofer.iem.safe.llm.SafeLlmSettings
import de.fraunhofer.iem.safe.llm.SafeProviderChangeListener
import de.fraunhofer.iem.safe.study.StudyModeSettings
import java.awt.Desktop
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * "Tools | SAFE" settings page. Lets the user pick an active LLM/agent provider and
 * configure each provider's endpoint, model, temperature, and API key independently —
 * switching providers in the dropdown loads that provider's stored values into the form,
 * so the four configurations don't trample each other.
 *
 * When the active provider is [ProviderKind.SAFE_AGENT] an extra "Backend LLM" combo
 * appears so the user can decide which other provider's saved config the agent service
 * should use under the hood (or "service defaults" to fall back to the service's env vars).
 */
class SafeSettingsConfigurable : Configurable {

    private val settings = SafeLlmSettings.getInstance()

    private val providerCombo = ComboBox(ProviderKind.entries.toTypedArray()).apply {
        renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.displayName }
    }

    /** First entry is `null` ⇒ "Use service defaults" (service falls back to its env vars). */
    private val agentLlmCombo = ComboBox(
        arrayOf<ProviderKind?>(null) +
            ProviderKind.entries.filter { it != ProviderKind.SAFE_AGENT }.toTypedArray()
    ).apply {
        renderer = com.intellij.ui.SimpleListCellRenderer.create("") {
            it?.displayName ?: "Use service defaults"
        }
    }
    private val agentLlmRow = JBLabel("Backend LLM:")

    private val endpointField = JBTextField()
    private val modelField = JBTextField()
    private val temperatureField = JBTextField()
    private val apiKeyField = JBPasswordField()

    // ── Study mode ────────────────────────────────────────────────────────
    private val studySettings = StudyModeSettings.getInstance()
    private val studyEnabled = JBCheckBox("Enable user-study telemetry")
    private val participantField = JBTextField()
    private val telemetryDirField = JBTextField()
    private val openTelemetryFolderButton = JButton("Open folder")

    private var rootPanel: JPanel? = null
    private var modified = false

    /**
     * Per-provider edit buffer. Switching providers in the combo saves the form into the
     * outgoing provider's draft and loads the incoming provider's draft into the form.
     * Apply writes every draft back to [settings] in one go.
     */
    private data class Draft(var endpoint: String, var model: String, var temperature: String)
    private val drafts = mutableMapOf<ProviderKind, Draft>()

    /**
     * Pending API-key edits, per provider. Empty string ⇒ the user did not retype the key,
     * so we leave the saved value alone (so an empty field never wipes credentials).
     */
    private val keyDrafts = mutableMapOf<ProviderKind, String>()

    /** Tracks whether the form is currently bound to this provider's draft, so swap-on-change is well-defined. */
    private var currentProvider: ProviderKind = ProviderKind.AZURE_OPENAI

    /** Suppresses field-change → modified flips while we programmatically repopulate the form. */
    private var loadingForm = false

    override fun getDisplayName(): String = "SAFE"

    override fun createComponent(): JComponent {
        openTelemetryFolderButton.addActionListener {
            val raw = telemetryDirField.text.trim()
            val dir = if (raw.isNotBlank()) File(raw)
            else File(System.getProperty("user.home"), ".safe-telemetry")
            if (!dir.exists()) dir.mkdirs()
            runCatching { Desktop.getDesktop().open(dir) }
        }

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Provider:"), providerCombo, 1, false)
            .addLabeledComponent(JBLabel("Endpoint URL:"), endpointField, 1, false)
            .addLabeledComponent(JBLabel("Model / deployment:"), modelField, 1, false)
            .addLabeledComponent(JBLabel("Temperature:"), temperatureField, 1, false)
            .addLabeledComponent(JBLabel("API key:"), apiKeyField, 1, false)
            .addLabeledComponent(agentLlmRow, agentLlmCombo, 1, false)
            .addComponent(TitledSeparator("Study mode"))
            .addComponent(studyEnabled)
            .addLabeledComponent(JBLabel("Participant id:"), participantField, 1, false)
            .addLabeledComponent(
                JBLabel("Telemetry folder (blank ⇒ <project>/.idea/safe-telemetry):"),
                telemetryDirField, 1, false,
            )
            .addComponent(openTelemetryFolderButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel.border = JBUI.Borders.empty(10)

        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onFieldChanged()
            override fun removeUpdate(e: DocumentEvent?) = onFieldChanged()
            override fun changedUpdate(e: DocumentEvent?) = onFieldChanged()
        }
        endpointField.document.addDocumentListener(docListener)
        modelField.document.addDocumentListener(docListener)
        temperatureField.document.addDocumentListener(docListener)
        apiKeyField.document.addDocumentListener(docListener)
        participantField.document.addDocumentListener(docListener)
        telemetryDirField.document.addDocumentListener(docListener)
        studyEnabled.addActionListener { onFieldChanged() }

        providerCombo.addActionListener {
            val next = providerCombo.selectedItem as? ProviderKind ?: return@addActionListener
            if (next == currentProvider) return@addActionListener
            // Save the form into the outgoing provider's draft, then load the incoming one.
            captureFormIntoDraft(currentProvider)
            currentProvider = next
            loadDraftIntoForm(next)
            updateAgentLlmRowVisibility()
            modified = true
        }

        agentLlmCombo.addActionListener {
            if (loadingForm) return@addActionListener
            modified = true
        }

        rootPanel = panel
        reset()
        recordTelemetry("settings.opened")
        return panel
    }

    /** The "Backend LLM" row is only meaningful when SAFE Agent is the active provider. */
    private fun updateAgentLlmRowVisibility() {
        val show = currentProvider == ProviderKind.SAFE_AGENT
        agentLlmRow.isVisible = show
        agentLlmCombo.isVisible = show
    }

    private fun onFieldChanged() {
        if (loadingForm) return
        modified = true
    }

    private fun captureFormIntoDraft(provider: ProviderKind) {
        val draft = drafts.getOrPut(provider) { draftFromSettings(provider) }
        draft.endpoint = endpointField.text.trim()
        draft.model = modelField.text.trim()
        draft.temperature = temperatureField.text.trim()
        val typed = String(apiKeyField.password).trim()
        if (typed.isNotEmpty()) keyDrafts[provider] = typed
    }

    private fun loadDraftIntoForm(provider: ProviderKind) {
        val draft = drafts.getOrPut(provider) { draftFromSettings(provider) }
        loadingForm = true
        try {
            endpointField.text = draft.endpoint
            modelField.text = draft.model
            temperatureField.text = draft.temperature
            apiKeyField.text = ""
        } finally {
            loadingForm = false
        }
    }

    private fun draftFromSettings(provider: ProviderKind): Draft {
        val cfg = settings.configFor(provider)
        return Draft(cfg.endpointUrl, cfg.model, cfg.temperature)
    }

    override fun isModified(): Boolean = modified

    override fun apply() {
        val previousProvider = settings.providerKind
        val nextProvider = providerCombo.selectedItem as ProviderKind

        // Make sure the form's current values are captured into the active draft before
        // we flush to settings — otherwise the live edits the user just typed would be
        // lost if they hit Apply without first switching the combo.
        captureFormIntoDraft(currentProvider)

        for ((provider, draft) in drafts) {
            settings.updateConfig(
                provider,
                draft.endpoint,
                draft.model,
                draft.temperature.ifBlank { "0.0" },
            )
        }

        for ((provider, key) in keyDrafts) {
            if (key.isNotEmpty()) settings.setApiKeyFor(provider, key)
        }
        keyDrafts.clear()

        settings.agentLlmProvider = agentLlmCombo.selectedItem as? ProviderKind
        settings.providerKind = nextProvider

        // Study-mode fields are independent of the per-provider drafts.
        studySettings.enabled = studyEnabled.isSelected
        studySettings.participantId = participantField.text.trim()
        studySettings.telemetryDir = telemetryDirField.text.trim()

        modified = false

        if (previousProvider != nextProvider) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(SafeProviderChangeListener.TOPIC)
                .providerChanged(nextProvider.id)
        }
    }

    override fun reset() {
        drafts.clear()
        keyDrafts.clear()
        currentProvider = settings.providerKind
        providerCombo.selectedItem = currentProvider
        loadDraftIntoForm(currentProvider)
        loadingForm = true
        try {
            agentLlmCombo.selectedItem = settings.agentLlmProvider
            studyEnabled.isSelected = studySettings.enabled
            participantField.text = studySettings.participantId
            telemetryDirField.text = studySettings.telemetryDir
        } finally {
            loadingForm = false
        }
        updateAgentLlmRowVisibility()
        modified = false
    }

    override fun disposeUIResources() {
        recordTelemetry("settings.closed")
        rootPanel = null
    }

    /**
     * Settings telemetry isn't tied to a single project — it's an app-level
     * configurable. Emit on every open project so the participant's session
     * file captures the event regardless of which project is in focus.
     */
    private fun recordTelemetry(event: String) {
        if (!StudyModeSettings.getInstance().enabled) return
        for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
            de.fraunhofer.iem.safe.study.TelemetryRecorder.getInstance(project)
                .record(event = event)
        }
    }
}
