package de.fraunhofer.iem.safe.llm

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Application-level settings for the SAFE LLM client. Configuration is stored per-provider
 * so the user can keep separate endpoint/model/temperature values for Azure OpenAI, the
 * SAFE Agent service, etc., and switch between them without losing the others' settings.
 *
 * Non-secret values live in `safeLlmSettings.xml`. API keys are stored separately via
 * IntelliJ's [PasswordSafe], keyed off `(provider, endpoint)`.
 */
@Service(Service.Level.APP)
@State(name = "SafeLlmSettings-Red", storages = [Storage("safeLlmSettings-red.xml")])
class SafeLlmSettings : PersistentStateComponent<SafeLlmSettings.PersistedState> {

    /** Per-provider configuration. One [PersistedState] holds many of these. */
    @Tag("provider")
    class ProviderConfig {
        var providerId: String = ""
        var endpointUrl: String = ""
        var model: String = ""
        var temperature: String = "0.0"
    }

    class PersistedState {
        /** Currently-selected provider id (matches [ProviderKind.id]). */
        var providerId: String = ProviderKind.AZURE_OPENAI.id

        /**
         * When the active provider is [ProviderKind.SAFE_AGENT], this points at another
         * provider whose [ProviderConfig] supplies the LLM the agent service should run
         * (model/endpoint/key/temperature). `null` means "let the service use its own
         * env-var defaults" — the original SAFER behaviour.
         */
        var agentLlmProviderId: String? = null

        /**
         * Configurations for each provider the user has set up. The annotation pins the
         * XML shape so we don't accidentally rely on whatever default the platform's
         * XmlSerializer would pick.
         */
        @XCollection(propertyElementName = "providers", elementName = "provider")
        var providers: MutableList<ProviderConfig> = mutableListOf()

        // ── Legacy fields ──────────────────────────────────────────────────────
        // Pre-per-provider versions of this plugin stored a single shared
        // (endpoint, model, temperature) tuple at the top level of
        // safeLlmSettings.xml. We still deserialise those fields so an existing
        // user's settings migrate cleanly into the new `providers` list. After
        // migration these are reset to their defaults; they remain in the schema
        // so old XML can still be loaded.
        var endpointUrl: String = ""
        var model: String = ""
        var temperature: String = "0.0"
    }

    private val logger = Logger.getInstance(SafeLlmSettings::class.java)
    private var myState = PersistedState()

    override fun getState(): PersistedState = myState

    override fun loadState(state: PersistedState) {
        myState = state
        migrateLegacyConfig()
    }

    /**
     * If we loaded XML that has the old shared `endpointUrl/model/temperature` fields and
     * no `providers` list yet, fold the legacy values into a [ProviderConfig] for the
     * active provider so the user keeps what they had.
     */
    private fun migrateLegacyConfig() {
        if (myState.providers.isNotEmpty()) return
        val hasLegacy = myState.endpointUrl.isNotBlank() ||
            myState.model.isNotBlank() ||
            myState.temperature != "0.0"
        if (!hasLegacy) return

        myState.providers.add(ProviderConfig().apply {
            providerId = myState.providerId
            endpointUrl = myState.endpointUrl
            model = myState.model
            temperature = myState.temperature
        })
        myState.endpointUrl = ""
        myState.model = ""
        myState.temperature = "0.0"
        logger.info("Migrated legacy SAFE LLM settings into per-provider config for provider=${myState.providerId}")
    }

    var providerKind: ProviderKind
        get() = ProviderKind.fromId(myState.providerId)
        set(value) { myState.providerId = value.id }

    /**
     * Backend LLM the SAFE Agent service should use. `null` means "use the service's own
     * env-var defaults". When non-null, [SafeAgentProvider][de.fraunhofer.iem.safe.llm.SafeAgentProvider]
     * forwards this provider's saved [ProviderConfig] + API key in the `/explain` payload.
     */
    var agentLlmProvider: ProviderKind?
        get() = myState.agentLlmProviderId?.let { id ->
            ProviderKind.entries.firstOrNull { it.id == id && it != ProviderKind.SAFE_AGENT }
        }
        set(value) { myState.agentLlmProviderId = value?.id }

    /** Endpoint URL for the *currently active* provider. Read-only here — write via [updateConfig]. */
    val endpointUrl: String
        get() = findConfig(providerKind)?.endpointUrl.orEmpty()

    val model: String
        get() = findConfig(providerKind)?.model.orEmpty()

    val temperature: String
        get() = findConfig(providerKind)?.temperature ?: "0.0"

    /**
     * Returns the saved configuration for [provider], or `null` if none has been written yet.
     * Reads must NOT mutate state — that's why this no longer lazy-creates an entry the way
     * the previous version did. [updateConfig] is the only path that writes new entries.
     */
    fun findConfig(provider: ProviderKind): ProviderConfig? =
        myState.providers.firstOrNull { it.providerId == provider.id }

    /**
     * Returns the saved configuration for [provider], creating an empty entry if needed.
     * Use this from the Settings UI where lazy creation is desired (so the form has
     * something to bind to). Read-only callers should use [findConfig] to avoid side effects.
     */
    fun configFor(provider: ProviderKind): ProviderConfig {
        findConfig(provider)?.let { return it }
        return ProviderConfig().apply {
            providerId = provider.id
            myState.providers.add(this)
        }
    }

    fun updateConfig(provider: ProviderKind, endpointUrl: String, model: String, temperature: String) {
        val cfg = configFor(provider)
        cfg.endpointUrl = endpointUrl
        cfg.model = model
        cfg.temperature = temperature
    }

    /** Returns the API key for the active provider, or null if none stored. */
    fun apiKey(): String? = PasswordSafe.instance.getPassword(credentialAttributes(providerKind))

    /** Returns the API key for an arbitrary provider — needed by the Settings UI when editing inactive providers. */
    fun apiKeyFor(provider: ProviderKind): String? =
        PasswordSafe.instance.getPassword(credentialAttributes(provider))

    fun setApiKey(value: String?) = setApiKeyFor(providerKind, value)

    fun setApiKeyFor(provider: ProviderKind, value: String?) {
        val attrs = credentialAttributes(provider)
        if (value.isNullOrEmpty()) {
            PasswordSafe.instance.set(attrs, null)
        } else {
            PasswordSafe.instance.set(attrs, Credentials("safe-llm", value))
        }
    }

    private fun credentialAttributes(provider: ProviderKind): CredentialAttributes {
        // Use the provider's own endpoint in the service-name so different endpoints under
        // the same provider can hold separate keys (matches the previous behaviour).
        val endpoint = findConfig(provider)?.endpointUrl.orEmpty()
        val key = "${provider.id}|$endpoint"
        return CredentialAttributes(generateServiceName("SAFE-Red", key))
    }

    companion object {
        fun getInstance(): SafeLlmSettings = ApplicationManager.getApplication().service()
    }
}
