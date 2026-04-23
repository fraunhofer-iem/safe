package de.fraunhofer.iem.safe.llm

import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import java.net.http.HttpClient
import java.time.Duration

@Service(Service.Level.APP)
class AzureOpenAIService {

    private val logger = Logger.getInstance(AzureOpenAIService::class.java)
    private val gson = Gson()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    val llmConfig = LlmConfig()
    // Configure these via settings or environment variables
    private val endpoint = llmConfig.apiURL
        ?: "https://<your-resource>.openai.azure.com"
    private val apiKey = llmConfig.apiKey ?: ""
    private val deploymentName = llmConfig.model ?: "o4-mini"
    private val apiVersion = "2025-08-07"

    fun explain(prompt: String): String {
        return try {
        val client = OpenAIOkHttpClient.builder()
            .baseUrl(llmConfig.apiURL)
            .apiKey(llmConfig.apiKey)
            .build()


        val params = ChatCompletionCreateParams.builder()
            .addMessage(
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(prompt)
                        .build()
                )
            )
            .model(llmConfig.model) // Azure ignores this, uses deployment name from URL
            .build()

        val completion: ChatCompletion = client.chat().completions().create(params)

        completion.choices().firstOrNull()
            ?.message()
            ?.content()
            ?.orElse(null)
            ?: "Error: Empty response from model"
    } catch (e: Exception) {
        logger.error("Azure OpenAI call failed", e)
        "Error: Failed to get explanation - ${e.message}"
    }
    }

    // DTOs
    data class ChatRequest(
        val messages: List<ChatMessage>
    )

    data class ChatMessage(
        val role: String,
        val content: String
    )

    data class ChatResponse(
        val choices: List<Choice>
    )

    data class Choice(
        val message: ChatMessage
    )

    companion object {
        fun getInstance(): AzureOpenAIService = service()
    }
}