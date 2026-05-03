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

/**
 * Application-level settings for the SAFE LLM client. Non-secret values (provider id,
 * endpoint URL, model name, temperature) live in `safeLlmSettings.xml`. The API key is
 * stored separately via IntelliJ's [com.intellij.ide.passwordSafe.PasswordSafe], keyed off `(provider, endpoint)` so
 * users can switch providers without losing their credentials for the others.
 */
@Service(Service.Level.APP)
@State(name = "SafeLlmSettings", storages = [Storage("safeLlmSettings.xml")])
class SafeLlmSettings : PersistentStateComponent<SafeLlmSettings.State> {

    class State {
        var providerId: String = ProviderKind.AZURE_OPENAI.id
        var endpointUrl: String = ""
        var model: String = ""
        var temperature: String = "0.0"
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    var providerKind: ProviderKind
        get() = ProviderKind.fromId(myState.providerId)
        set(value) { myState.providerId = value.id }

    var endpointUrl: String
        get() = myState.endpointUrl
        set(value) { myState.endpointUrl = value }

    var model: String
        get() = myState.model
        set(value) { myState.model = value }

    var temperature: String
        get() = myState.temperature
        set(value) { myState.temperature = value }

    fun apiKey(): String? = PasswordSafe.instance.getPassword(credentialAttributes())

    fun setApiKey(value: String?) {
        val attrs = credentialAttributes()
        if (value.isNullOrEmpty()) {
            PasswordSafe.instance.set(attrs, null)
        } else {
            PasswordSafe.instance.set(attrs, Credentials("safe-llm", value))
        }
    }

    private fun credentialAttributes(): CredentialAttributes {
        val key = "${providerKind.id}|${endpointUrl}"
        return CredentialAttributes(generateServiceName("SAFE", key))
    }

    companion object {
        fun getInstance(): SafeLlmSettings = ApplicationManager.getApplication().service()
    }
}