package de.fraunhofer.iem.fixmysast.llmService

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.fraunhofer.iem.fixmysast.sast.dataModel.ExpertiseLevel
import de.fraunhofer.iem.fixmysast.sast.dataModel.SASTIssue

/**
 * This class provides the functionality of sending LLM request and receiving the explanation for the SAST issue.
 *
 * @author Alexandra Fomina
 */
object LLMClient {
    private val mapper = jacksonObjectMapper()

    /**
     * Sends the prompts to LLM based on the expertise level and parses the response for the explanation of SAST issue
     */
    fun getExplanation(issue: SASTIssue, level: ExpertiseLevel = ExpertiseLevel.INTERMEDIATE): String? {
        val prompt = PromptTemplate.build(issue, level)
        val requestBody = buildRequestBody(prompt)
        val llmConfig = getLLMConfig(true)

        return try {
            val response = HttpService.postJson(llmConfig.apiURL, llmConfig.apiKey, requestBody)
            parseResponse(response)
        } catch (e: Exception) {
            e.printStackTrace()
            "LLM error: ${e.message ?: e.toString()}"
        }
    }

    /**
     * Build the complete prompt by combining the system and user prompts
     */
    private fun buildRequestBody(prompt: String): String {
        val promptJson = mapper.writeValueAsString(prompt)
        return """
            {
              "messages": [
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": $promptJson}
              ]
            }
        """.trimIndent()
    }

    /**
     * Parses the given responses by the LLM. The explanation is in the field choices -> message -> content
     */
    private fun parseResponse(response: String): String? {
        val result: Map<String, Any> = mapper.readValue(response)
        val choices = result["choices"] as? List<Map<String, Any>>
        val message = choices?.firstOrNull()?.get("message") as? Map<String, Any>
        return message?.get("content") as? String
    }
}