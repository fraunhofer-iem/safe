package de.fraunhofer.iem.fixmysast.sast
import com.intellij.openapi.project.Project
import java.io.File

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

import org.jetbrains.annotations.NonNls


@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonReport(
    val version: String?,
    val results: List<JsonResult>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonResult(
    @JsonProperty("check_id") val checkId: String?,
    val path: String,
    val start: Position,
    val end: Position,
    val extra: Extra,
    val severity: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Position(val line: Int, val col: Int, val offset: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Extra(
    val message: String,
    val metavars: JsonNode?,
    val metadata: Metadata,
    val severity: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Metadata(
    val cwe: List<String>,
    val owasp: List<String>?,
    val likelihood: String?,
    val impact: String?,
    val confidence: String?,
    @JsonProperty("category") val category: String?
)

/* --------------------------- JSON‑only parser ----------------------------- */

object JsonParser {

    fun parse(
        project: Project,
        filePath: @NonNls String
    ): Results {
        val jsonFile = File(filePath)

        try {

            val jsonContent = jsonFile.readText()
            val mapper = jacksonObjectMapper()
            val jsonReport: JsonReport = mapper.readValue(jsonContent)

            val issues = mutableListOf<Issue>()

            jsonReport.results.forEach { r ->
                val type    = r.checkId ?: "Unknown"
                val message = r.extra.message
                val tags    = (r.extra.metadata.owasp ?: emptyList()) +
                        (r.extra.metadata.cwe   ?: emptyList())
                val explanation = r.extra.message
                val confidence = r.extra.metadata.confidence
                val cwe = r.extra.metadata.cwe ?: emptyList()
                val severity = r.extra.severity ?: "Unknown"
                val owasp = r.extra.metadata.owasp ?: emptyList()
                val impact = r.extra.metadata.impact ?: ""


                val codeSnippet = extractCodeSnippet(
                    project,
                    r.path,
                    r.start.line,
                    r.end.line
                )

//                val type: String,
//                val message: String,
//                val tags: List<String>,
//                //Add line numbers
//                val codeSnippet: String,
//                var explanation: String? = "",
//                val confidence: String,
//                val severity: String,
//                val cwe: List<String>
                    issues.add(Issue(type, message, tags, codeSnippet, explanation, confidence, severity, cwe, owasp, impact))
                }

            return Results(
                jsonFile.absolutePath,
                project.basePath!!,
                "Semgrep", issues
            )

        } catch (e: Exception) {
            println("Error parsing SARIF from project: ${e.message}")
            logger.error(e.message)
        }
        return TODO("Provide the return value")
    }

    private fun extractCodeSnippet(
        project: Project,
        relativePath: String,
        startLine: Int,
        endLine: Int
    ): String {
        val file = File(project.basePath, relativePath)
        if (!file.exists()) return "File not found: $relativePath"

        val lines = file.readLines()
        val end   = endLine.coerceAtMost(lines.size)

        return if (startLine in 1..lines.size)
            lines.subList(startLine - 1, end).joinToString("\n")
        else
            "Invalid line range: $startLine–$endLine"
    }

}