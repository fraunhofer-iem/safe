package de.fraunhofer.iem.fixmysast.util

import java.util.Properties

object AppProperties {
    private val APP_PROP_FILE_NAME = "app.properties"

    private val props = loadAppProperties()

    private fun loadAppProperties(): Properties {
        val tempProps = Properties()

        val stream = this::class.java.classLoader.getResourceAsStream(APP_PROP_FILE_NAME)

        tempProps.load(stream)

        return tempProps
    }

    fun get(key: String): String? = props.getProperty(key)

    val apiKey: String? get() = props.getProperty("api.key")

    val openaiApiUrl: String? get() = props.getProperty("openai.api.url")

    val olamaApiUrl: String? get() = props.getProperty("olama.api.url")

    val llmModel: String get() = props.getProperty("llm.model")

    val llmTemperature: String get() = props.getProperty("llm.temperature")
}