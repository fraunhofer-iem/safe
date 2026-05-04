package de.fraunhofer.iem.safe.llm

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import de.fraunhofer.iem.safe.sast.TaintTrace
import de.fraunhofer.iem.safe.sast.VulnerabilityInfo
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
            ProviderKind.SAFE_AGENT -> SafeAgentProvider(
                endpoint = endpoint.ifBlank { "http://127.0.0.1:8800" },
                settings = settings,
            )
        }
    }
}

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
    override fun configurationProblem(): String? {
        val missing = buildList {
            if (endpoint.isBlank()) add("endpoint URL")
            if (model.isBlank()) add("deployment name")
            if (apiKey.isBlank()) add("API key")
        }
        return if (missing.isEmpty()) null else missing.joinToString(", ")
    }
    override fun explain(request: ExplainRequest): String {
        configurationProblem()?.let { return "Error: Azure OpenAI is missing $it. Open SAFE settings to configure." }
        val prompt = VulnerabilityPromptBuilder.buildPrompt(request.vulnerabilities)
        return try {
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
    override fun configurationProblem(): String? {
        val missing = buildList {
            if (model.isBlank()) add("model")
            if (apiKey.isBlank()) add("API key")
        }
        return if (missing.isEmpty()) null else missing.joinToString(", ")
    }
    override fun explain(request: ExplainRequest): String {
        configurationProblem()?.let { return "Error: OpenAI is missing $it. Open SAFE settings to configure." }
        val prompt = VulnerabilityPromptBuilder.buildPrompt(request.vulnerabilities)
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
    override fun configurationProblem(): String? {
        val missing = buildList {
            if (model.isBlank()) add("model")
            if (apiKey.isBlank()) add("API key")
        }
        return if (missing.isEmpty()) null else missing.joinToString(", ")
    }
    override fun explain(request: ExplainRequest): String {
        configurationProblem()?.let { return "Error: Anthropic is missing $it. Open SAFE settings to configure." }
        val prompt = VulnerabilityPromptBuilder.buildPrompt(request.vulnerabilities)
        return try {
            val uri = "${endpoint.trimEnd('/')}/messages"
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
    override fun configurationProblem(): String? =
        if (model.isBlank()) "model" else null
    override fun explain(request: ExplainRequest): String {
        configurationProblem()?.let { return "Error: Ollama is missing $it. Open SAFE settings to configure." }
        val prompt = VulnerabilityPromptBuilder.buildPrompt(request.vulnerabilities)
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

/**
 * Posts to the SAFE agentic explainer service (FastAPI, default `http://127.0.0.1:8800`).
 * The service runs an LLM agent with code-reading tools and streams the response over
 * Server-Sent Events. We accumulate `token` events and return the full text once `done`
 * arrives — the SAFE tool window only consumes the final string today.
 */
class SafeAgentProvider(
    private val endpoint: String,
    private val settings: SafeLlmSettings,
) : LlmProvider {
    override val id = ProviderKind.SAFE_AGENT.id
    override val displayName = ProviderKind.SAFE_AGENT.displayName

    /**
     * If the user picked a backend LLM in Settings, validate that it has the credentials
     * the service will need. When the backend is "service defaults" (null), we send no
     * LLM fields and the service falls back to its env vars — nothing for us to validate.
     */
    override fun configurationProblem(): String? {
        val backend = settings.agentLlmProvider ?: return null
        val cfg = settings.findConfig(backend)
        val key = settings.apiKeyFor(backend)
        val missing = buildList {
            when (backend) {
                ProviderKind.AZURE_OPENAI -> {
                    if (cfg?.endpointUrl.isNullOrBlank()) add("endpoint URL")
                    if (cfg?.model.isNullOrBlank()) add("deployment name")
                    if (key.isNullOrBlank()) add("API key")
                }
                ProviderKind.OPENAI, ProviderKind.ANTHROPIC -> {
                    if (cfg?.model.isNullOrBlank()) add("model")
                    if (key.isNullOrBlank()) add("API key")
                }
                ProviderKind.OLLAMA -> {
                    if (cfg?.model.isNullOrBlank()) add("model")
                }
                ProviderKind.SAFE_AGENT -> {} // unreachable — getter filters this out
            }
        }
        return if (missing.isEmpty()) null
        else "${backend.displayName} (selected as agent backend) is missing ${missing.joinToString(", ")}"
    }

    override fun explain(request: ExplainRequest): String {
        val rootPath = request.project.basePath
            ?: return "Error: SAFE Agent requires a project root, but the current project has none."
        val vuln = request.vulnerabilities.firstOrNull()
            ?: return "Error: No vulnerability selected."
        val filePath = vuln.filePath
            ?: return "Error: SAFE Agent requires a file path on the finding, but none was provided."

        val payload = buildAgentPayload(rootPath, filePath, vuln)
        val uri = "${endpoint.trimEnd('/')}/explain"
        return try {
            consumeSse(uri, payload)
                .ifBlank { "Error: Empty response from SAFE Agent." }
        } catch (e: Exception) {
            logger.warn("SAFE Agent call failed", e)
            "Error: Failed to get explanation — ${e.message}"
        }
    }

    private fun buildAgentPayload(rootPath: String, filePath: String, vuln: VulnerabilityInfo): String {
        val obj = JsonObject().apply {
            addProperty("rootpath", rootPath)
            addProperty("filepath", filePath)
            addProperty("issue_context", vuln.message ?: "")
            addProperty("cwe", vuln.cwe?.id ?: "Unknown CWE")
            addProperty("rule_id", vuln.inspectionId ?: "")
            addProperty("rule_description", vuln.inspectionName ?: "")
            vuln.severity?.let { addProperty("severity", it) }
            vuln.startLine?.let { addProperty("start_line", it) }
            vuln.endLine?.let { addProperty("end_line", it) }
            vuln.snippet?.let { addProperty("snippet", it) }
            add("taint_flow", encodeTaintFlow(vuln.traces))
            attachBackendLlm(this)
        }
        return gson.toJson(obj)
    }

    /**
     * If the user picked a backend LLM in Settings, copy that provider's saved config +
     * API key into the request as `llm_*` fields. The service uses these to instantiate
     * a fresh LangChain LLM per request, falling through to its env-var defaults when no
     * fields are present.
     */
    private fun attachBackendLlm(obj: JsonObject) {
        val backend = settings.agentLlmProvider ?: return
        val cfg = settings.findConfig(backend) ?: return
        obj.addProperty("llm_provider", backend.id)
        val resolvedEndpoint = when (backend) {
            ProviderKind.OPENAI -> cfg.endpointUrl.ifBlank { "https://api.openai.com/v1" }
            ProviderKind.ANTHROPIC -> cfg.endpointUrl.ifBlank { "https://api.anthropic.com/v1" }
            ProviderKind.OLLAMA -> cfg.endpointUrl.ifBlank { "http://localhost:11434" }
            else -> cfg.endpointUrl
        }
        if (resolvedEndpoint.isNotBlank()) obj.addProperty("llm_endpoint", resolvedEndpoint)
        if (cfg.model.isNotBlank()) obj.addProperty("llm_model", cfg.model)
        cfg.temperature.toDoubleOrNull()?.let { obj.addProperty("llm_temperature", it) }
        settings.apiKeyFor(backend)?.takeIf { it.isNotBlank() }?.let { obj.addProperty("llm_api_key", it) }
    }

    /**
     * Flattens all traces into a single ordered list of step dicts. The agent prompt
     * numbers them as 1.1, 1.2, …; multi-trace findings (rare) lose the inter-trace
     * boundary, but the plugin almost always sends a single trace per finding.
     */
    private fun encodeTaintFlow(traces: List<TaintTrace>): JsonArray {
        val arr = JsonArray()
        for (trace in traces) {
            for (step in trace.steps) {
                val o = JsonObject()
                step.filePath?.let { o.addProperty("file", it) }
                step.startLine?.let { o.addProperty("line", it) }
                step.message?.let { o.addProperty("message", it) }
                arr.add(o)
            }
        }
        return arr
    }

    /**
     * Posts [payload] and consumes the FastAPI service's `text/event-stream`. Returns
     * the concatenated text from every `{"type": "token", "content": …}` event, with
     * SSE-escaped `\n` un-escaped back to real newlines. Stops on `done` or `error`.
     */
    private fun consumeSse(uri: String, payload: String): String {
        // Force HTTP/1.1 — uvicorn (the FastAPI server) speaks HTTP/1.1 by default, and
        // the JDK's HTTP/2 default occasionally serialises small POST bodies in a way the
        // h2c→h1.1 downgrade path drops. Pinning the version sidesteps the whole dance.
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(uri))
            .version(HttpClient.Version.HTTP_1_1)
            // Agent runs may take minutes (tool use, code reading). 10 minutes is generous
            // but bounded so a hung server eventually surfaces as an error.
            .timeout(Duration.ofMinutes(10))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(payload))

        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() !in 200..299) {
            // Drain the (small) error body so users see the FastAPI validation detail
            // rather than just a bare status code. Truncate at 500 chars to keep logs tidy.
            val body = response.body().limit(50).toList().joinToString("\n").take(500)
            throw RuntimeException("HTTP ${response.statusCode()}: $body")
        }

        val acc = StringBuilder()
        for (rawLine in response.body()) {
            val line = rawLine.trim()
            if (!line.startsWith("data:")) continue
            val json = line.removePrefix("data:").trim().ifBlank { continue }
            val event = try {
                JsonParser.parseString(json).asJsonObject
            } catch (_: Exception) {
                continue
            }
            when (event.get("type")?.asString) {
                "token" -> event.get("content")?.asString
                    ?.replace("\\n", "\n")
                    ?.let { acc.append(it) }
                "done" -> return acc.toString()
                "error" -> {
                    val msg = event.get("message")?.asString ?: "(no detail)"
                    throw RuntimeException("Agent error: $msg")
                }
            }
        }
        return acc.toString()
    }
}
