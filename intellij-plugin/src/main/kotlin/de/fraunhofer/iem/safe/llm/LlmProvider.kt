package de.fraunhofer.iem.safe.llm

/**
 * Pluggable LLM backend. Implementations adapt the SAFE plugin to a specific HTTP API
 * (Azure OpenAI, OpenAI, Anthropic, local Ollama, …). Each implementation is responsible
 * for its own auth and request shaping; SAFE just hands it a prompt string and expects
 * the model's reply.
 */
interface LlmProvider {
    /** A short, stable id used to identify the provider in settings (`"openai"`, `"ollama"`, …). */
    val id: String

    /** Human-readable name surfaced in settings UI. */
    val displayName: String

    /** Sends [prompt] and returns the model's text reply, or an error message starting with `Error:`. */
    fun explain(prompt: String): String
}

/** Available providers, in display order. */
enum class ProviderKind(val id: String, val displayName: String) {
    AZURE_OPENAI("azure-openai", "Azure OpenAI"),
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic"),
    OLLAMA("ollama", "Ollama (local)");

    companion object {
        fun fromId(id: String?): ProviderKind = entries.firstOrNull { it.id == id } ?: AZURE_OPENAI
    }
}
