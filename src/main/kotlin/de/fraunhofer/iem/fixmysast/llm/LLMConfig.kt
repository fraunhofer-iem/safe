package de.fraunhofer.iem.fixmysast.llm

import de.fraunhofer.iem.fixmysast.core.AppProperties
import java.util.*

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
    override val apiURL = AppProperties.openaiApiUrl!!
    override val apiKey = AppProperties.apiKey!!
}

object OlamaLLMConfig : LLMConfig {
    override val apiURL = AppProperties.olamaApiUrl!!
    override val apiKey = AppProperties.apiKey!!
}

fun getLLMConfig(): LLMConfig {
    if (AppProperties.llmModel.lowercase(Locale.getDefault()) == "olama") {
        return OlamaLLMConfig
    }

    // By default, always provide openai model if there is any error in the app.properties file.
    return OpenAiLLMConfig
}