package de.fraunhofer.iem.safe.llm

/**
 * Configuration for LLM
 *
 * Reads everything from [SafeSettings] (Settings | Tools | SAFE). The plugin
 * has no other source of configuration; if values are missing the LLM call
 * will fail with an authentication / endpoint error and the user is expected
 * to fix it in the settings page.
 *
 * @author Alexandra Fomina
 * @author Ranjith
 */
class LlmConfig() {
    private val settings = SafeSettings.getInstance()

    val platform: String = settings.platform
    val apiURL: String = settings.apiUrl
    val apiKey: String = settings.apiKey().orEmpty()
    val model: String = settings.model
    val temperature: String = settings.temperature.ifBlank { "0.0" }
}
