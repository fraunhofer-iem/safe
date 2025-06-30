package de.fraunhofer.iem.fixmysast.sast
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jetbrains.rd.util.string.print
import io.ktor.utils.io.printStack
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
object LLMClient {
    // Ollama URL
    // private const val API_URL = "https://hoc-lx-gpu02.ad.iem-hoc.de:8083/api/generate" //llama3.1:8b
    //Open AI URL https://fhgenie-api-iem-dev-assist.openai.azure.com/api/generate
    private const val API_URL = "https://fhgenie-api-iem-dev-assist.openai.azure.com/api/generate"

    //Open AI Key:
    private const val API_KEY = ""

    fun getExplanation(issue: SASTIssue, level: ExpertiseLevel = ExpertiseLevel.BEGINNER): String? {

        val mapper = jacksonObjectMapper()

        val prompt = buildPromptByLevel(issue, level)

        return try {

            val apiUrl =
                "https://fhgenie-api-iem-dev-assist.openai.azure.com/openai/deployments/gpt-4o-2024-08-06/chat/completions?api-version=2024-02-01"

            val connection = URL(apiUrl).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"

            connection.setRequestProperty("api-key", API_KEY)

            connection.setRequestProperty("Content-Type", "application/json")

            connection.doOutput = true

//            val requestBody = """

//                    {

//                    "model": "llama3.1:8b",

//                    "prompt": "$jsonSafePrompt",

//                    "stream": false,

//                    "temperature": 1.0

//                    }

//                """.trimIndent()

            val requestBody = """

            {

              "messages": [

                {"role": "system", "content": "You are a helpful assistant."},

                {"role": "user", "content": ${mapper.writeValueAsString(prompt)}}

              ]

            }

        """.trimIndent()

            OutputStreamWriter(connection.outputStream).use { it.write(requestBody) }

            val responseCode = connection.responseCode

            val response = if (responseCode == HttpURLConnection.HTTP_OK) {

                connection.inputStream.bufferedReader().readText()

            } else {

                val errorMsg = connection.errorStream?.bufferedReader()?.readText()

                throw IOException("HTTP $responseCode Error: $errorMsg") as Throwable

            }
//response

            //val result: Map<String, Any> = mapper.readValue(response)
            val result: Map<String, Any> = mapper.readValue(response)
            val choices = result["choices"] as? List<Map<String, Any>>
            val message = choices?.get(0)?.get("message") as? Map<String, Any>
            message?.get("content") as? String
            //val responseValue = result["response"]

            //responseValue

        } catch (e: Exception) {

            e.printStack()

            println("Raw exceptions: ${e::class.simpleName} - ${e.message}")

            "LLM error: ${e.message ?: e.toString()}"

        }

    }

    fun buildPrompt(issue: SASTIssue): String {
        //Import script that's been saved?
        //return "You are an expert in software security and you need to explain a specific SAST error to a developer who is not trained in security protocols. Provide a clear, hand-held explanation for ${issue.type}"
        return """
            You are an expert in software security and you need to explain a specific SAST error to a developer who is not trained in security protocols. 
            Provide a clear, detailed, and markdown-formatted explanation.
            Please remember that the developer is a beginner, so make sure to hold their hand throughout the explanation.
            Please use the following structure and bold section headers using Markdown:
            
            ___
            
            
            ### **1. Overview and Description.**
              -Original Message:
                - Type: **${issue.type}**
                - Description "${issue.message}"?
                - Tag: ${issue.tags[0]}
          
              - If possible, map this vulnerability to a known OWASP Top Category, e.x., OWASP A052021.
              - Explain what this vulnerability is in **simple terms**. 
              - Describe **how it occurs** in the code.
              - Provide a **realistic example scenario** where this vulnerability could be exploited.
              - Describe how an attacker might **manipulate this code**.
              
            ___

            
            ### **2. Suggested Code Fix.**
            Provide a **secure version of this code** that at the minimum:
                - Defines a safe variable or directory
                - Applies input sanitization
                - Constructs path or output safely
                - Includes proper validation checks
                
         ```java
         // Replace this with a secure code fix
         ${issue.codeSnippet}
        """.trimIndent()
    }

    /**
     * Different prompt templates for each expertise level.
     */
    private fun buildPromptByLevel(issue: SASTIssue, level: ExpertiseLevel): String = when (level) {
        ExpertiseLevel.BEGINNER -> """
            You are an expert in software security and you need to explain a specific SAST error to a brand‑new software developer understand security.
            In no more than 120 words, explain the vulnerability below in plain, everyday language.
            Avoid jargon; use analogies when useful.
            Here are the relevant details:
                - Type: **${issue.type}**
                - Description "${issue.message}"?
                - Tag: ${issue.tags[0]}
            Using this, suggest a secure cod fix:\n${issue.codeSnippet}
            Also, include the relevant details at the start:
            -Original Message:
                - Type: **${issue.type}**
                - Description "${issue.message}"?
                - Tag: ${issue.tags[0]}
        """.trimIndent()
        ExpertiseLevel.INTERMEDIATE -> """
            You are an expert in software security advising a developer with 3‑5 years of experience.
            Give a concise (≤200 word) explanation of the vulnerability and how it works in code.
            Include a one‑paragraph mitigation strategy.
            Here are the relevant details:
                - Type: **${issue.type}**
                - Description "${issue.message}"?
                - Tag: ${issue.tags[0]}
            Using this, suggest a secure cod fix:\n${issue.codeSnippet}
            Also, include the relevant details at the start:
            -Original Message:
                - Type: **${issue.type}**
                - Description "${issue.message}"?
                - Tag: ${issue.tags[0]}
        """.trimIndent()
        ExpertiseLevel.ADVANCED -> """
            Act as an application‑security expert.
            Provide a comprehensive technical deep‑dive (≤350 words) into the vulnerability, including attack vectors, root cause, and relevant standards (e.g., CWE, OWASP).
            End with references to RFCs or papers.
            Here are the relevant details:
                - Type: **${issue.type}**
                - Description "${issue.message}"?
                - Tag: ${issue.tags[0]}
            Using this, suggest a secure cod fix:\n${issue.codeSnippet}
            Also, include the relevant details at the start:
            -Original Message:
                - Type: **${issue.type}**
                - Description "${issue.message}"?
                - Tag: ${issue.tags[0]}
        """.trimIndent()
    }
}