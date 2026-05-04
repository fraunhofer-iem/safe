package de.fraunhofer.iem.safe.llm

import com.intellij.openapi.project.Project
import de.fraunhofer.iem.safe.sast.VulnerabilityInfo

/**
 * Pluggable explanation backend. Implementations adapt the SAFE plugin to either a
 * stateless chat-completion API (Azure OpenAI, OpenAI, Anthropic, local Ollama, …) or
 * the agentic SAFE service. Each implementation is responsible for its own auth and
 * request shaping; the plugin just hands it an [ExplainRequest] and expects the model's
 * reply as plain text in SAFE's marker format.
 */
interface LlmProvider {
    /** A short, stable id used to identify the provider in settings (`"openai"`, `"safe-agent"`, …). */
    val id: String

    /** Human-readable name surfaced in settings UI. */
    val displayName: String

    /** Returns the model's text reply for [request], or an error message starting with `Error:`. */
    fun explain(request: ExplainRequest): String

    /**
     * Returns `null` when this provider is ready to make calls, or a short user-facing
     * description of what's missing (e.g. "API key", "endpoint URL and deployment name")
     * so the caller can offer to take the user to Settings.
     */
    fun configurationProblem(): String? = null
}

/**
 * Inputs to an explanation call. Carries both the structured findings (used by the
 * agentic provider) and the project context (used to resolve the project root). Plain
 * LLM providers feed [vulnerabilities] through [VulnerabilityPromptBuilder] internally.
 */
data class ExplainRequest(
    val project: Project,
    val vulnerabilities: List<VulnerabilityInfo>,
)

/** Available providers, in display order. */
enum class ProviderKind(val id: String, val displayName: String) {
    AZURE_OPENAI("azure-openai", "Azure OpenAI"),
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic"),
    OLLAMA("ollama", "Ollama (local)"),
    SAFE_AGENT("safe-agent", "SAFE Agent");

    companion object {
        fun fromId(id: String?): ProviderKind = entries.firstOrNull { it.id == id } ?: AZURE_OPENAI
    }
}
