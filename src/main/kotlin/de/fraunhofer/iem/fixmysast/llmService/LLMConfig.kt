package de.fraunhofer.iem.fixmysast.llmService

//TODO: This is temporary! This has to be configured in env file or system env. Must not hard code API key.
interface LLMConfig {
    val apiURL: String
    val apiKey: String
}

object OpenAiLLMConfig : LLMConfig {
    override val apiURL = "https://fhgenie-api-iem-dev-assist.openai.azure.com/openai/deployments/gpt-4o-2024-08-06/chat/completions?api-version=2024-02-01"
    override val apiKey = "81afdae23b3c4050a77b3115415a11c3"
}

object OlamaLLMConfig : LLMConfig {
    override val apiURL = "Ollama_Api_Url"
    override val apiKey = "81afdae23b3c4050a77b3115415a11c3"
}

fun getLLMConfig(isOpenAI: Boolean): LLMConfig {
    if (isOpenAI) {
        return OpenAiLLMConfig
    }

    return OlamaLLMConfig
}