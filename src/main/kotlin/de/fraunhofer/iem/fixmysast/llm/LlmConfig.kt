package de.fraunhofer.iem.fixmysast.llm

import de.fraunhofer.iem.fixmysast.util.AppProperties
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
    val temperature: String
}

object OpenAiLLMConfig : LLMConfig {
    override val apiURL = AppProperties.openaiApiUrl!!
    override val apiKey = AppProperties.apiKey!!
    override val temperature = AppProperties.llmTemperature
}

object OlamaLLMConfig : LLMConfig {
    override val apiURL = AppProperties.olamaApiUrl!!
    override val apiKey = AppProperties.apiKey!!
    override val temperature = AppProperties.llmTemperature
}

fun getLLMConfig(): LLMConfig {
    if (AppProperties.llmModel.lowercase(Locale.getDefault()) == "olama") {
        return OlamaLLMConfig
    }

    // By default, always provide openai model if there is any error in the app.properties file.
    return OpenAiLLMConfig
}