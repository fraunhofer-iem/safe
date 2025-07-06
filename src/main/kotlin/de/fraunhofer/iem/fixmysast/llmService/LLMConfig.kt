package de.fraunhofer.iem.fixmysast.llmService

import de.fraunhofer.iem.fixmysast.core.AppProperties

//TODO: This is temporary! This has to be configured in env file or system env. Must not hard code API key.
interface LLMConfig {
    val apiURL: String
    val apiKey: String
}

object OpenAiLLMConfig : LLMConfig {
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