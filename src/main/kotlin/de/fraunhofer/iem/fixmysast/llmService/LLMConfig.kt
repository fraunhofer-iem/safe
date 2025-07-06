package de.fraunhofer.iem.fixmysast.llmService

import de.fraunhofer.iem.fixmysast.core.AppProperties

/**
 * Configuration for LLM
 *
 * Please add more configuration parameters here in the future.
 *
 * @author Alexandra Fomina
 * @author Ranjith
 */
interface LLMConfig {
    val apiURL: String
    val apiKey: String
}

object OpenAiLLMConfig : LLMConfig {
    //TODO: Analyze and check should we keep the url here or in the app.properties.
    override val apiURL = "https://fhgenie-api-iem-dev-assist.openai.azure.com/openai/deployments/gpt-4o-2024-08-06/chat/completions?api-version=2024-02-01"
    override val apiKey = AppProperties.apiKey!!
}

object OlamaLLMConfig : LLMConfig {
    override val apiURL = "Ollama_Api_Url"
    override val apiKey = AppProperties.apiKey!!
}

fun getLLMConfig(isOpenAI: Boolean): LLMConfig {
    if (isOpenAI) {
        return OpenAiLLMConfig
    }

    return OlamaLLMConfig
}