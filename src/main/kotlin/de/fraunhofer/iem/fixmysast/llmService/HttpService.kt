package de.fraunhofer.iem.fixmysast.llmService

import java.io.*
import java.net.HttpURLConnection
import java.net.URL

object HttpService {
    fun postJson(url: String, apiKey: String, jsonBody: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }

        OutputStreamWriter(connection.outputStream).use {
            it.write(jsonBody)
        }

        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}