package de.fraunhofer.iem.safe.llm

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import de.fraunhofer.iem.safe.llm.SafeLlmSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = Logger.getInstance("de.fraunhofer.iem.safe.llm.LlmProviders")
private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .build()
private val gson = Gson()

/**
 * Builds the right [LlmProvider] for the user's saved settings. Reads the API key out
 * of [PasswordSafe][com.intellij.ide.passwordSafe.PasswordSafe] via [SafeLlmSettings].
 */
object LlmProviderFactory {
    fun current(settings: SafeLlmSettings = SafeLlmSettings.getInstance()): LlmProvider {
        val apiKey = settings.apiKey().orEmpty()
        val endpoint = settings.endpointUrl
        val model = settings.model
        return when (settings.providerKind) {
            ProviderKind.AZURE_OPENAI -> AzureOpenAiProvider(endpoint, apiKey, model)
            ProviderKind.OPENAI -> OpenAiProvider(endpoint.ifBlank { "https://api.openai.com/v1" }, apiKey, model)
            ProviderKind.ANTHROPIC -> AnthropicProvider(endpoint.ifBlank { "https://api.anthropic.com/v1" }, apiKey, model)
            ProviderKind.OLLAMA -> OllamaProvider(endpoint.ifBlank { "http://localhost:11434" }, model)
        }
    }
}

/** Common shape for chat-completion-style providers. */
private fun postJson(uri: String, headers: Map<String, String>, body: String): String {
    val builder = HttpRequest.newBuilder()
        .uri(URI.create(uri))
        .timeout(Duration.ofSeconds(120))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
    headers.forEach { (k, v) -> builder.header(k, v) }
    val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
        throw RuntimeException("HTTP ${response.statusCode()}: ${response.body()}")
    }
    return response.body()
}

private fun chatPayload(model: String, prompt: String, temperature: Double?): String {
    val obj = JsonObject().apply {
        addProperty("model", model)
        add("messages", gson.toJsonTree(listOf(mapOf("role" to "user", "content" to prompt))))
        if (temperature != null) addProperty("temperature", temperature)
    }
    return gson.toJson(obj)
}

private fun firstChoiceContent(json: String): String {
    val root = JsonParser.parseString(json).asJsonObject
    val choices = root.getAsJsonArray("choices") ?: return ""
    if (choices.isEmpty) return ""
    val message = choices[0].asJsonObject.getAsJsonObject("message") ?: return ""
    return message.get("content")?.asString.orEmpty()
}

class AzureOpenAiProvider(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
) : LlmProvider {
    override val id = ProviderKind.AZURE_OPENAI.id
    override val displayName = ProviderKind.AZURE_OPENAI.displayName
    override fun explain(prompt: String): String {
        if (apiKey.isBlank() || endpoint.isBlank() || model.isBlank()) {
            return "Error: Configure the SAFE plugin's Azure OpenAI endpoint, key, and deployment in Settings | Tools | SAFE."
        }
        return try {
            // Azure expects the deployment name in the URL path, not the body.
            val uri = "${endpoint.trimEnd('/')}/openai/deployments/$model/chat/completions?api-version=2024-08-01-preview"
            val body = chatPayload(model, prompt, temperature = null)
            firstChoiceContent(postJson(uri, mapOf("api-key" to apiKey), body))
                .ifBlank { "Error: Empty response from Azure OpenAI." }
        } catch (e: Exception) {
            logger.warn("Azure OpenAI call failed", e)
            "Error: Failed to get explanation — ${e.message}"
        }
    }
}

class OpenAiProvider(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
) : LlmProvider {
    override val id = ProviderKind.OPENAI.id
    override val displayName = ProviderKind.OPENAI.displayName
    override fun explain(prompt: String): String {
        if (apiKey.isBlank() || model.isBlank()) {
            return "Error: Configure the SAFE plugin's OpenAI key and model in Settings | Tools | SAFE."
        }
        return try {
            val uri = "${endpoint.trimEnd('/')}/chat/completions"
            val body = chatPayload(model, prompt, temperature = null)
            firstChoiceContent(postJson(uri, mapOf("Authorization" to "Bearer $apiKey"), body))
                .ifBlank { "Error: Empty response from OpenAI." }
        } catch (e: Exception) {
            logger.warn("OpenAI call failed", e)
            "Error: Failed to get explanation — ${e.message}"
        }
    }
}

class AnthropicProvider(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
) : LlmProvider {
    override val id = ProviderKind.ANTHROPIC.id
    override val displayName = ProviderKind.ANTHROPIC.displayName
    override fun explain(prompt: String): String {
        if (apiKey.isBlank() || model.isBlank()) {
            return "Error: Configure the SAFE plugin's Anthropic key and model in Settings | Tools | SAFE."
        }
        return try {
            val uri = "${endpoint.trimEnd('/')}/messages"
            // Anthropic uses a slightly different shape: top-level `model`, `max_tokens`, and `messages` with role/content.
            val obj = JsonObject().apply {
                addProperty("model", model)
                addProperty("max_tokens", 1024)
                add("messages", gson.toJsonTree(listOf(mapOf("role" to "user", "content" to prompt))))
            }
            val headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
            )
            val raw = postJson(uri, headers, gson.toJson(obj))
            val root = JsonParser.parseString(raw).asJsonObject
            val content = root.getAsJsonArray("content") ?: return "Error: Anthropic returned no content."
            if (content.isEmpty) return "Error: Empty response from Anthropic."
            content[0].asJsonObject.get("text")?.asString
                ?: "Error: Could not parse Anthropic response."
        } catch (e: Exception) {
            logger.warn("Anthropic call failed", e)
            "Error: Failed to get explanation — ${e.message}"
        }
    }
}

class OllamaProvider(
    private val endpoint: String,
    private val model: String,
) : LlmProvider {
    override val id = ProviderKind.OLLAMA.id
    override val displayName = ProviderKind.OLLAMA.displayName
    override fun explain(prompt: String): String {
        if (model.isBlank()) {
            return "Error: Configure an Ollama model name in Settings | Tools | SAFE."
        }
        return try {
            val uri = "${endpoint.trimEnd('/')}/api/generate"
            val obj = JsonObject().apply {
                addProperty("model", model)
                addProperty("prompt", prompt)
                addProperty("stream", false)
            }
            val raw = postJson(uri, emptyMap(), gson.toJson(obj))
            val root = JsonParser.parseString(raw).asJsonObject
            root.get("response")?.asString.orEmpty()
                .ifBlank { "Error: Empty response from Ollama." }
        } catch (e: Exception) {
            logger.warn("Ollama call failed", e)
            "Error: Failed to get explanation — ${e.message}"
        }
    }
}
