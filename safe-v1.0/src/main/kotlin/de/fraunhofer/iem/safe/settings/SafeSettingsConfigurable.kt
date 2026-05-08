package de.fraunhofer.iem.safe.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import de.fraunhofer.iem.safe.llm.SafeSettings
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * "Tools | SAFE" settings page. Captures the five fields the LLM client needs:
 * platform, endpoint URL, model, temperature, API key. Non-secret values are
 * persisted via [SafeSettings]; the API key goes to [PasswordSafe][com.intellij.ide.passwordSafe.PasswordSafe].
 */
class SafeSettingsConfigurable : Configurable {

    private val settings = SafeSettings.getInstance()

    private val platformCombo = ComboBox(arrayOf("openai", "ollama"))
    private val apiUrlField = JBTextField()
    private val modelField = JBTextField()
    private val temperatureField = JBTextField()
    private val apiKeyField = JBPasswordField()

    private var rootPanel: JPanel? = null
    private var modified = false

    override fun getDisplayName(): String = "SAFE"

    override fun createComponent(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Platform:"), platformCombo, 1, false)
            .addLabeledComponent(JBLabel("Endpoint URL:"), apiUrlField, 1, false)
            .addLabeledComponent(JBLabel("Model:"), modelField, 1, false)
            .addLabeledComponent(JBLabel("Temperature:"), temperatureField, 1, false)
            .addLabeledComponent(JBLabel("API key:"), apiKeyField, 1, false)
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
        platformCombo.addActionListener { modified = true }

        rootPanel = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean = modified

    override fun apply() {
        settings.platform = (platformCombo.selectedItem as? String).orEmpty()
        settings.apiUrl = apiUrlField.text.trim()
        settings.model = modelField.text.trim()
        settings.temperature = temperatureField.text.trim().ifBlank { "0.0" }
        // Empty key field ⇒ the user did not retype their key, so leave whatever is
        // already in PasswordSafe alone. Only overwrite when something was typed.
        val typed = String(apiKeyField.password).trim()
        if (typed.isNotEmpty()) settings.setApiKey(typed)
        modified = false
    }

    override fun reset() {
        platformCombo.selectedItem = settings.platform.ifBlank { "openai" }
        apiUrlField.text = settings.apiUrl
        modelField.text = settings.model
        temperatureField.text = settings.temperature.ifBlank { "0.0" }
        apiKeyField.text = ""
        modified = false
    }

    override fun disposeUIResources() {
        rootPanel = null
    }
}
