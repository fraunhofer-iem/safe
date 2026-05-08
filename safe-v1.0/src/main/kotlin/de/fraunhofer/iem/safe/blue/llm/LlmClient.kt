package de.fraunhofer.iem.safe.blue.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import de.fraunhofer.iem.safe.blue.sast.Issue

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
        val systemPrompt = PromptTemplate.getSystemPrompt()
        val userPrompt = PromptTemplate.buildUserPrompt(issue, experienceLevel, project)

        // Persistent cache first — if we already have an answer for this exact
        // prompt body (across sessions), reuse it without hitting the LLM.
        val persistentCache = ExplanationCacheService.getInstance(project)
        val cacheKey = ExplanationCacheService.keyOf(systemPrompt + "\n" + userPrompt + "\n" + llmConfig.temperature)
        persistentCache.get(cacheKey)?.let { return it }

        val client = OpenAIOkHttpClient.builder()
            .baseUrl(llmConfig.apiURL)
            .apiKey(llmConfig.apiKey)
            .build()

        val params = ChatCompletionCreateParams.builder()
            .addSystemMessage(systemPrompt)
            .addUserMessage(userPrompt)
            .model(llmConfig.model)
            .temperature(llmConfig.temperature.toDouble())
            .build()

        val bodyKey = params._body().toString()
        explanationCache[bodyKey]?.let {
            persistentCache.put(cacheKey, it)
            return it
        }

        val chatCompletion: ChatCompletion = client.chat().completions().create(params)
        val response = chatCompletion.choices().first().message()._content().toString()
        explanationCache[bodyKey] = response
        persistentCache.put(cacheKey, response)
        return response
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

        val persistentCache = ExplanationCacheService.getInstance(project)
        val cacheKey = ExplanationCacheService.keyOf(requestBody)
        persistentCache.get(cacheKey)?.let { return it }

        if (explanationCache.containsKey(requestBody)) {
            val cached = explanationCache[requestBody]!!
            persistentCache.put(cacheKey, cached)
            return cached
        }

        val response = sendRequest(requestBody)
        explanationCache[requestBody] = response
        persistentCache.put(cacheKey, response)
        return response
    }

    /**
     * Returns the persisted explanation for `(issue, level)` without making any
     * network call, or `null` when nothing is cached yet. Used by the
     * explanation panel to surface previously-generated answers as soon as the
     * user clicks a finding, instead of waiting for an explicit re-trigger.
     *
     * The cache key is built from the same components [sendRequest] uses
     * (`systemPrompt + "\n" + userPrompt + "\n" + temperature`) so a hit here
     * mirrors what `sendRequest` would resolve to.
     */
    fun getCachedExplanation(issue: Issue?, project: Project, experienceLevel: String): String? {
        val llmConfig = LlmConfig()
        val systemPrompt = PromptTemplate.getSystemPrompt()
        val userPrompt = PromptTemplate.buildUserPrompt(issue, experienceLevel, project)
        val key = ExplanationCacheService.keyOf(
            systemPrompt + "\n" + userPrompt + "\n" + llmConfig.temperature
        )
        return ExplanationCacheService.getInstance(project).get(key)
    }

    fun updateExplanation(issue: Issue, project: Project): String? {
        val level = PropertiesComponent.getInstance(project).getValue("de.fraunhofer.iem.safe.expertiseValue")

        val requestBody = buildRequestBody(
            PromptTemplate.getSystemPrompt(),
            PromptTemplate.buildUserPrompt(issue, level, project), "0.0"
        )

        // updateExplanation is the "force re-fetch" entry point — bypass any
        // cache, call the LLM fresh, then store the new response under both layers.
        val response = sendRequest(requestBody)
        explanationCache[requestBody] = response
        ExplanationCacheService.getInstance(project)
            .put(ExplanationCacheService.keyOf(requestBody), response)
        return response
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