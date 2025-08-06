package de.fraunhofer.iem.fixmysast.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import de.fraunhofer.iem.fixmysast.sast.Issue

/**
 * This class provides the functionality of sending LLM request and receiving the explanation for the SAST issue.
 *
 * @author Alexandra Fomina
 */
object LlmClient {
    private val mapper = jacksonObjectMapper()

    private val explanationCache: MutableMap<String, String> = mutableMapOf()

    /**
     * Sends the prompts to LLM based on the expertise level and parses the response for the explanation of SAST issue
     */
    fun getExplanation(issue: Issue?, project: Project): String? {

        val level = PropertiesComponent.getInstance(project).getValue("de.fraunhofer.iem.fixmysast.expertiseValue")         // ← persisted default

        println("Level being used inside getExplanation is... $level")

        val requestBody = buildRequestBody(
            PromptTemplate.getSystemPrompt(),
            PromptTemplate.buildUserPrompt(issue, level, project),
            "0.0"
        )

        if (explanationCache.containsKey(requestBody)) {
            //println("Found response for: " + issue?.type)
            //println(requestBody)
            return explanationCache[requestBody]!!
        } else {
            //println("Send request for: "+ issue?.type)
            //println(requestBody)
            explanationCache[requestBody] = sendRequest(requestBody)

            return explanationCache[requestBody]!!
        }
    }

    fun updateExplanation(issue: Issue, project: Project): String? {
        val level = PropertiesComponent.getInstance(project).getValue("Fixmysast.expertiseValue")

        val requestBody = buildRequestBody(
            PromptTemplate.getSystemPrompt(),
            PromptTemplate.buildUserPrompt(issue, level, project), "0.0"
        )
        explanationCache[requestBody] = sendRequest(requestBody)

        return explanationCache[requestBody]!!
    }
    fun sendRequest(requestBody: String): String {

        //val future = CompletableFuture<String>()

        val llmConfig = getLLMConfig()

        try {
            val response = HttpService.postJson(
                llmConfig,
                requestBody
            )

            return parseResponse(response)!!
            // future.complete(response)
        } catch (e: Exception) {
            e.printStackTrace()
            "LLM error: ${e.message ?: e.toString()}"
            "No response received"
            return "No response received"
        }
    }

    /**
     * Build the complete prompt by combining the system and user prompts
     */
    private fun buildRequestBody(systemPrompt: String, userPrompt: String, temperature: String): String {
        val usrPromptJson = mapper.writeValueAsString(userPrompt)
        val sysPromptJson = mapper.writeValueAsString(systemPrompt)
        return """
            {
              "messages": [
                {"role": "system", "content": $sysPromptJson},
                {"role": "user", "content": $usrPromptJson}
              ],
              "temperature": $temperature
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