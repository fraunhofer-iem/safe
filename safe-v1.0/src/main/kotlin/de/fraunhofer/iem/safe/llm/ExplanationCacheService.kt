package de.fraunhofer.iem.safe.llm

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Project-level explanation cache that survives IDE restarts. Backed by an
 * `<options>/safeExplanationCache.xml` per project. Keyed by a SHA-256 of the
 * full prompt body so different prompt+temperature combinations don't collide.
 *
 * Added for the user-study build of v1.0 to replace [LlmClient]'s in-process
 * `MutableMap` cache, which previously had to be repopulated from scratch on
 * every IDE restart.
 */
@Service(Service.Level.PROJECT)
@State(name = "SafeExplanationCache", storages = [Storage("safeExplanationCache.xml")])
class ExplanationCacheService : PersistentStateComponent<ExplanationCacheService.CacheState> {

    /**
     * One persisted cache entry. Public mutable fields so IntelliJ's XmlSerializer
     * can round-trip them without extra annotations.
     */
    class CacheEntry {
        var key: String = ""
        var response: String = ""
    }

    class CacheState {
        var entries: MutableList<CacheEntry> = mutableListOf()
    }

    private var myState = CacheState()

    override fun getState(): CacheState = myState
    override fun loadState(state: CacheState) { myState = state }

    fun get(key: String): String? = myState.entries.firstOrNull { it.key == key }?.response

    fun put(key: String, response: String) {
        val existing = myState.entries.firstOrNull { it.key == key }
        if (existing != null) {
            existing.response = response
        } else {
            val entry = CacheEntry()
            entry.key = key
            entry.response = response
            myState.entries.add(entry)
        }
    }

    fun clear() {
        myState.entries.clear()
    }

    companion object {
        fun getInstance(project: Project): ExplanationCacheService = project.service()

        /**
         * Stable hash over the full prompt body. The cache deliberately keys on the
         * exact bytes the LLM would see, so any change in system/user prompt or
         * temperature produces a fresh cache slot.
         */
        fun keyOf(promptBody: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(promptBody.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
