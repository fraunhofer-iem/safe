package de.fraunhofer.iem.safe.blue.llm

import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * This class provides the functionality to send the REST request to the server by sending the JSON object as a POST method
 *
 * @author Alexandra Fomina
 * @author Ranjith
 */
object HttpService {
    fun postJson(config: LlmConfig, jsonBody: String): String {
        val connection = (URL(config.apiURL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("api-key", config.apiKey)
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }

        OutputStreamWriter(connection.outputStream).use {
            it.write(jsonBody)
        }

        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}