package de.fraunhofer.iem.safe.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import de.fraunhofer.iem.safe.sast.Issue

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
    fun sendRequest(issue: Issue?, project: Project, experienceLevel: String): String? {

        val llmConfig = LlmConfig()

        val client = OpenAIOkHttpClient.builder()
            .baseUrl(llmConfig.apiURL)
            .apiKey(llmConfig.apiKey)
            .build()

        val params = ChatCompletionCreateParams.builder()
            .addSystemMessage(PromptTemplate.getSystemPrompt())
            .addUserMessage(PromptTemplate.buildUserPrompt(issue, experienceLevel, project))
            .model(llmConfig.model)
            .temperature(llmConfig.temperature.toDouble())
            .build()

        val chatCompletion: ChatCompletion = client.chat().completions().create(params)

          if (explanationCache.containsKey(params._body().toString())) {
              return explanationCache[params._body().toString()]!!
          } else {
              explanationCache[params._body().toString()] = chatCompletion.choices().first().message()._content().toString()

              return explanationCache[params._body().toString()]!!
          }
    }


    /**
     * Sends the prompts to LLM based on the expertise level and parses the response for the explanation of SAST issue
     */
    fun getExplanation(issue: Issue?, project: Project, experienceLevel: String): String? {

        val requestBody = buildRequestBody(
            PromptTemplate.getSystemPrompt(),
            PromptTemplate.buildUserPrompt(issue, experienceLevel, project),
            "0.0"
        )

        if (explanationCache.containsKey(requestBody)) {
            return explanationCache[requestBody]!!
        } else {
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
        println(
            ">>>>>>>>>>>>>>>>>>>>>>>>>>>\n" +
                    requestBody + "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>"
        )
        val llmConfig = LlmConfig()

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