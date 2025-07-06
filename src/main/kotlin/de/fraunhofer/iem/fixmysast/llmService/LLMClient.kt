package de.fraunhofer.iem.fixmysast.llmService

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.fraunhofer.iem.fixmysast.sast.dataModel.ExpertiseLevel
import de.fraunhofer.iem.fixmysast.sast.dataModel.SASTIssue

object LLMClient {
    private val mapper = jacksonObjectMapper()

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

    private fun parseResponse(response: String): String? {
        val result: Map<String, Any> = mapper.readValue(response)
        val choices = result["choices"] as? List<Map<String, Any>>
        val message = choices?.firstOrNull()?.get("message") as? Map<String, Any>
        return message?.get("content") as? String
    }
}