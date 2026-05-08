package de.fraunhofer.iem.safe.blue.llm

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
 * Application-level settings for the SAFE plugin's LLM client. Non-secret values
 * (platform id, endpoint URL, model, temperature) are persisted to
 * `safeSettings.xml`; the API key is stored separately via IntelliJ's
 * [PasswordSafe], keyed off `(platform, endpoint)` so changing endpoints does
 * not leak credentials between accounts.
 *
 * This is the sole source of LLM configuration in the user-study build of v1.0
 * — the previous checked-in `app.properties` file is no longer consulted.
 */
@Service(Service.Level.APP)
@State(name = "SafeSettings-Blue", storages = [Storage("safeSettings-blue.xml")])
class SafeSettings : PersistentStateComponent<SafeSettings.PersistedState> {

    class PersistedState {
        var platform: String = ""
        var apiUrl: String = ""
        var model: String = ""
        var temperature: String = ""
    }

    private var myState = PersistedState()

    override fun getState(): PersistedState = myState
    override fun loadState(state: PersistedState) { myState = state }

    var platform: String
        get() = myState.platform
        set(value) { myState.platform = value }

    var apiUrl: String
        get() = myState.apiUrl
        set(value) { myState.apiUrl = value }

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
        // Including the endpoint in the credential service-name lets the user keep
        // separate keys for, say, an internal Azure deployment and a public OpenAI
        // endpoint without retyping when switching between them.
        val key = "${platform.ifBlank { "default" }}|${apiUrl}"
        return CredentialAttributes(generateServiceName("SAFE-Blue", key))
    }

    /** True when the user has filled in at least the endpoint and model. */
    fun isConfigured(): Boolean = apiUrl.isNotBlank() && model.isNotBlank()

    companion object {
        fun getInstance(): SafeSettings = ApplicationManager.getApplication().service()
    }
}
