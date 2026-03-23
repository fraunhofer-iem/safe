package de.fraunhofer.iem.safe.llm

import de.fraunhofer.iem.safe.util.AppProperties

/**
 * Configuration for LLM
 *
 * Please add more configuration parameters here in the future.
 *
 * @author Alexandra Fomina
 * @author Ranjith
 */
class LlmConfig (){
    val platform = AppProperties.llmPlatform
    val apiURL = AppProperties.apiUrl!!
    val apiKey = AppProperties.apiKey!!
    val model = AppProperties.model!!
    val temperature = AppProperties.temperature!!
}