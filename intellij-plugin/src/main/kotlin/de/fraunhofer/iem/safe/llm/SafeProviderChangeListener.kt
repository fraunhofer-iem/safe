package de.fraunhofer.iem.safe.llm

import com.intellij.util.messages.Topic

/**
 * Fires when the user picks a different LLM/agent provider in Settings | Tools | SAFE.
 * The SAFE tool window subscribes to refresh its tree so each provider sees only the
 * explanations it produced.
 */
fun interface SafeProviderChangeListener {
    fun providerChanged(newProviderId: String)

    companion object {
        @JvmField
        val TOPIC: Topic<SafeProviderChangeListener> =
            Topic.create("SAFE LLM provider changed", SafeProviderChangeListener::class.java)
    }
}
