package de.fraunhofer.iem.fixmysast.sast

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.NonNls
import java.io.File


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
    @JsonProperty("dataflow_trace") val dataFlowTrace: DataFlowTrace?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DataFlowTrace(
    @JsonProperty("taint_source") val taintSource: List<Any>,
    @JsonProperty("taint_sink") val taintSink: List<Any>,
    @JsonProperty("intermediate_vars") val intermediate: List<IntermediateVar>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntermediateVar(
    val location: JsonLocation,
    val content: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonLocation(
    val path: String?,
    val start: Offset,
    val end: Offset
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Offset(
    val line: Int,
    val col: Int,
    val offset: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Metadata(
    val cwe: List<String>?,
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
    ): Results? {
        val jsonFile = File(filePath)

        try {

            val jsonContent = jsonFile.readText()
            val mapper = jacksonObjectMapper()
            val jsonReport: JsonReport = mapper.readValue(jsonContent)

            val issues = mutableListOf<Issue>()

            jsonReport.results.forEach { result ->
                val type    = result.checkId ?: "Unknown"
                val message = result.extra.message
                val tags    = (result.extra.metadata.owasp ?: emptyList()) +
                        (result.extra.metadata.cwe   ?: emptyList())


                val codeSnippet = extractCodeSnippet(
                    project,
                    result.path,
                    result.start.line,
                    result.end.line
                )

                    issues.add(
                        Issue(
                            type,
                            message,
                            tags,
                            result.extra.dataFlowTrace != null,
                            if (result.extra.dataFlowTrace != null) resolveDataFlowTrace(result.extra.dataFlowTrace) else null,
                            result.path,
                            result.start.line,
                            result.end.line,
                            codeSnippet
                        )
                    )
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
        return null
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

    fun resolveDataFlowTrace(trace: DataFlowTrace): List<DataFlowElement> {
        val elements = mutableListOf<DataFlowElement>()

        // Helper to convert the "list" structure for source or sink to a DataFlowElement
        fun parseSourceAndSink(taint: List<Any>, category: DataFlowCategory): DataFlowElement? {
            if (taint.size < 2) return null
            val details = taint[1]
            if (details !is List<*>) return null
            if (details.size < 2) return null

            val location = details[0]
            val name = details[1]

            // location should be a Map<String, Any>
            if (location !is Map<*, *>) return null
            if (name !is String) return null

            val startOffset: Int = (location["start"] as? Map<*, *>)?.get("offset")?.toString()?.toInt() ?: return null
            val endOffset: Int = (location["end"] as? Map<*, *>)?.get("offset")?.toString()?.toInt() ?: return null

            return DataFlowElement(
                name = name,
                startOffset = startOffset,
                endOffset = endOffset,
                type = category
            )
        }

        // Parse source
        parseSourceAndSink(trace.taintSource, DataFlowCategory.SOURCE)?.let { elements.add(it) }

        trace.intermediate.forEach { iv ->
            val startOffset = iv.location.start.offset.toInt()
            val endOffset = iv.location.end.offset.toInt()
            elements.add(
                DataFlowElement(
                    name = iv.content,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    type = DataFlowCategory.PROPAGATOR
                )
            )
        }

        // Parse sink
        parseSourceAndSink(trace.taintSink, DataFlowCategory.SINK)?.let { elements.add(it) }

        return elements
    }
}