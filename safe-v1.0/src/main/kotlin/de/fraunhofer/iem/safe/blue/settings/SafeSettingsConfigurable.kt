package de.fraunhofer.iem.safe.blue.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import de.fraunhofer.iem.safe.blue.llm.SafeSettings
import de.fraunhofer.iem.safe.blue.study.StudyModeSettings
import de.fraunhofer.iem.safe.blue.study.TelemetryRecorder
import java.awt.Desktop
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * "Tools | SAFE" settings page. Captures the five fields the LLM client needs:
 * platform, endpoint URL, model, temperature, API key. Non-secret values are
 * persisted via [SafeSettings]; the API key goes to
 * [PasswordSafe][com.intellij.ide.passwordSafe.PasswordSafe].
 *
 * Also hosts the *Study mode* section: enable telemetry, supply a participant
 * id, and (optionally) override the telemetry-folder path.
 */
class SafeSettingsConfigurable : Configurable {

    private val settings = SafeSettings.getInstance()

    private val platformCombo = ComboBox(arrayOf("openai", "ollama"))
    private val apiUrlField = JBTextField()
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
            .addLabeledComponent(JBLabel("Platform:"), platformCombo, 1, false)
            .addLabeledComponent(JBLabel("Endpoint URL:"), apiUrlField, 1, false)
            .addLabeledComponent(JBLabel("Model:"), modelField, 1, false)
            .addLabeledComponent(JBLabel("Temperature:"), temperatureField, 1, false)
            .addLabeledComponent(JBLabel("API key:"), apiKeyField, 1, false)
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
            override fun insertUpdate(e: DocumentEvent?) { modified = true }
            override fun removeUpdate(e: DocumentEvent?) { modified = true }
            override fun changedUpdate(e: DocumentEvent?) { modified = true }
        }
        apiUrlField.document.addDocumentListener(docListener)
        modelField.document.addDocumentListener(docListener)
        temperatureField.document.addDocumentListener(docListener)
        apiKeyField.document.addDocumentListener(docListener)
        participantField.document.addDocumentListener(docListener)
        telemetryDirField.document.addDocumentListener(docListener)
        platformCombo.addActionListener { modified = true }
        studyEnabled.addActionListener { modified = true }

        rootPanel = panel
        reset()
        recordTelemetry("settings.opened")
        return panel
    }

    override fun isModified(): Boolean = modified

    override fun apply() {
        settings.platform = (platformCombo.selectedItem as? String).orEmpty()
        settings.apiUrl = apiUrlField.text.trim()
        settings.model = modelField.text.trim()
        settings.temperature = temperatureField.text.trim().ifBlank { "0.0" }
        val typed = String(apiKeyField.password).trim()
        if (typed.isNotEmpty()) settings.setApiKey(typed)

        studySettings.enabled = studyEnabled.isSelected
        studySettings.participantId = participantField.text.trim()
        studySettings.telemetryDir = telemetryDirField.text.trim()

        modified = false
    }

    override fun reset() {
        platformCombo.selectedItem = settings.platform.ifBlank { "openai" }
        apiUrlField.text = settings.apiUrl
        modelField.text = settings.model
        temperatureField.text = settings.temperature.ifBlank { "0.0" }
        apiKeyField.text = ""

        studyEnabled.isSelected = studySettings.enabled
        participantField.text = studySettings.participantId
        telemetryDirField.text = studySettings.telemetryDir

        modified = false
    }

    override fun disposeUIResources() {
        recordTelemetry("settings.closed")
        rootPanel = null
    }

    /**
     * Settings telemetry isn't tied to a single project — emit on every open
     * project so the participant's session file captures the event regardless
     * of which project is in focus.
     */
    private fun recordTelemetry(event: String) {
        if (!StudyModeSettings.getInstance().enabled) return
        for (project in ProjectManager.getInstance().openProjects) {
            TelemetryRecorder.getInstance(project).record(event = event)
        }
    }
}
