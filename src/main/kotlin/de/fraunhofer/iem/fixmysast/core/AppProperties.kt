package de.fraunhofer.iem.fixmysast.core

import java.util.*

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
}