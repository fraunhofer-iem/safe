package de.fraunhofer.iem.safe.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import de.fraunhofer.iem.safe.llm.ProviderKind
import de.fraunhofer.iem.safe.llm.SafeLlmSettings
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * "Tools | SAFE" settings page. Lets the user pick an LLM provider and configure its
 * endpoint, model, temperature, and API key. The key is stored via PasswordSafe; the rest
 * via the application-level [SafeLlmSettings].
 */
class SafeSettingsConfigurable : Configurable {

    private val settings = SafeLlmSettings.getInstance()

    private val providerCombo = ComboBox(ProviderKind.entries.toTypedArray()).apply {
        renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.displayName }
    }
    private val endpointField = JBTextField()
    private val modelField = JBTextField()
    private val temperatureField = JBTextField()
    private val apiKeyField = JBPasswordField()

    private var rootPanel: JPanel? = null
    private var modified = false

    override fun getDisplayName(): String = "SAFE"

    override fun createComponent(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Provider:"), providerCombo, 1, false)
            .addLabeledComponent(JBLabel("Endpoint URL:"), endpointField, 1, false)
            .addLabeledComponent(JBLabel("Model / deployment:"), modelField, 1, false)
            .addLabeledComponent(JBLabel("Temperature:"), temperatureField, 1, false)
            .addLabeledComponent(JBLabel("API key:"), apiKeyField, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel.border = JBUI.Borders.empty(10)

        // Drive the modified flag so the IDE enables the Apply button.
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { modified = true }
            override fun removeUpdate(e: DocumentEvent?) { modified = true }
            override fun changedUpdate(e: DocumentEvent?) { modified = true }
        }
        endpointField.document.addDocumentListener(docListener)
        modelField.document.addDocumentListener(docListener)
        temperatureField.document.addDocumentListener(docListener)
        apiKeyField.document.addDocumentListener(docListener)
        providerCombo.addActionListener { modified = true }

        rootPanel = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean = modified

    override fun apply() {
        settings.providerKind = providerCombo.selectedItem as ProviderKind
        settings.endpointUrl = endpointField.text.trim()
        settings.model = modelField.text.trim()
        settings.temperature = temperatureField.text.trim().ifBlank { "0.0" }
        val newKey = String(apiKeyField.password).trim()
        // Only update the stored key when the field actually contains something. An empty
        // field on apply leaves the previously saved key intact (so we don't blow away
        // credentials if the user merely clears the field by reflex).
        if (newKey.isNotEmpty()) settings.setApiKey(newKey)
        modified = false
    }

    override fun reset() {
        providerCombo.selectedItem = settings.providerKind
        endpointField.text = settings.endpointUrl
        modelField.text = settings.model
        temperatureField.text = settings.temperature
        apiKeyField.text = ""
        modified = false
    }

    override fun disposeUIResources() {
        rootPanel = null
    }
}
