package de.fraunhofer.iem.safe.blue.sast

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.NonNls
import java.io.File

//Parses SARIF/XML

@JsonIgnoreProperties(ignoreUnknown = true)
data class SarifReport(val runs: List<Run>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Run(val tool: Tool, val results: List<Result>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Tool(val driver: Driver)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Driver(val name: String, val rules: List<Rule>?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Result(
    val ruleId: String?,
    val message: Message,
    val properties: JsonNode?,
    val locations: List<Location>?,
    val cwe: List<String>? = null,
    val severity: String? = null,
    val confidence: String? = null,
    val codeFlows: List<CodeFlow>? = null,
    /** Standard SARIF severity classification: `error`, `warning`, `note`, `none`. */
    val level: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Message(val text: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Location(val physicalLocation: PhysicalLocation?, val message: Message? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PhysicalLocation(val artifactLocation: ArtifactLocation?, val region: Region?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArtifactLocation(val uri: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Region(
    val startLine: Int?,
    val endLine: Int?,
    val startColumn: Int? = null,
    val endColumn: Int? = null,
    val snippet: Snippet? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Snippet(val text: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CodeFlow(val threadFlows: List<ThreadFlow>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ThreadFlow(val locations: List<ThreadFlowLocation>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ThreadFlowLocation(val location: Location? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Rule(val id: String?, val properties: JsonNode? = null)

val logger = Logger.getInstance("SAFE-Blue")

object SarifParser {

    fun parse(
        project: Project,
        filePath: @NonNls String
    ): Results {

        val sarifFile = File(filePath)

        try {

            val sarifContent = sarifFile.readText()
            val mapper = jacksonObjectMapper()
            val sarifReport: SarifReport = mapper.readValue(sarifContent)

            val issues = mutableListOf<Issue>()
            // Cache per-uri file content while turning SARIF threadFlow line/column
            // pairs into the character offsets the DataFlowPanel highlighter needs.
            // Most code-flows reference one file repeatedly, so a tiny LRU is plenty.
            val fileContentCache = mutableMapOf<String, String?>()

            sarifReport.runs.forEach { run ->
                run.results.forEach { result ->
                    val type = result.ruleId ?: "Unknown"
                    val message = result.message.text.orEmpty()
                    val tags = run.tool.driver.rules?.find { it.id == result.ruleId }?.properties?.get("tags")
                        ?.mapNotNull { it.asText() } ?: emptyList<String>()
                    val codeSnippet = extractCodeSnippet(project, result)

                    // Semgrep stuffs CWE-* / OWASP-* identifiers into the rule's
                    // `properties.tags`. Pull them out so the explanation panel
                    // has something to render in the title and OWASP badges
                    // instead of leaving everything null.
                    val cwe = tags.filter { it.startsWith("CWE-", ignoreCase = true) }
                        .takeIf { it.isNotEmpty() }
                    val owasp = tags.filter { it.startsWith("OWASP", ignoreCase = true) }
                        .takeIf { it.isNotEmpty() }

                    // SARIF's standard severity field is `level` ("error" / "warning" /
                    // "note" / "none"); pass it through as-is. Confidence / impact aren't
                    // part of the SARIF spec — Semgrep doesn't always emit them — so
                    // leave nullable and let the badge template fall back to "Unknown".
                    val severity = result.level
                    val confidence: String? = null
                    val impact: String? = null

                    val location = result.locations?.firstOrNull()?.physicalLocation
                    val dataFlowElements = resolveSarifDataFlow(project, result, fileContentCache)

                    issues.add(
                        Issue(
                            type, message, tags,
                            IssueLocation(
                                location?.artifactLocation?.uri!!,
                                codeSnippet,
                                location.region?.startLine!!,
                                location.region?.endLine!!
                            ),
                            null,
                            confidence, severity, cwe, owasp, impact,
                            dataFlowElements.isNotEmpty(),
                            dataFlowElements.takeIf { it.isNotEmpty() }
                        )
                    )
                }
            }

            return Results(
                sarifFile.absolutePath,
                project.basePath!!,
                "Semgrep", issues
            )

        } catch (e: Exception) {
            println("Error parsing SARIF from project: ${e.message}")
            logger.error(e.message)
        }
        return TODO("Provide the return value")
    }

    /*fun parseSarifFileFromResourceStream(
        project: Project,
        fileName: String
    ): Results {
        val inputStream =
            File(fileName).inputStream()
        val mapper = jacksonObjectMapper()
        val sarifReport: SarifReport = mapper.readValue(inputStream)
        val issues = mutableListOf<Issue>()
        sarifReport.runs.forEach { run ->
            run.results.forEach { result ->
                val type = result.ruleId ?: "Unknown"
                val message = result.message.text.orEmpty()
                val tags = result.properties?.get("tags")?.mapNotNull { it.asText() } ?: emptyList()
                val codeSnippet = extractCodeSnippet(project, result)
                issues.add(Issue(type, message, tags, codeSnippet))
            }
        }
        return Results(fileName, issues.groupBy { it.type })
    }*/

    private fun extractCodeSnippet(project: Project, result: Result): String {

        val projectPath = project.basePath
        val location = result.locations?.firstOrNull()?.physicalLocation
        val uri = location?.artifactLocation?.uri ?: return "No file path"
        val startLine = location.region?.startLine ?: return "No start line"
        val endLine = location.region?.endLine ?: startLine
        //attempt to read source file from resources
        val inputStream = File(File(projectPath), uri).inputStream()

        val lines = inputStream.bufferedReader().readLines()

        return if (startLine > 0 && startLine <= lines.size) {
            lines.subList(startLine - 1, endLine.coerceAtMost(lines.size)).joinToString("\n")
        } else {
            "Invalid line range: $startLine to $endLine"
        }
    }

    /**
     * Walks `result.codeFlows[0].threadFlows[0].locations` and produces a list of
     * [DataFlowElement] suitable for [de.fraunhofer.iem.safe.blue.ui.panel.DataFlowPanel].
     * The panel keys highlights off character offsets, so for each step we read
     * the referenced source file (cached in [fileContentCache]) and translate the
     * SARIF (line, column) pair into an absolute offset.
     *
     * If a step's file cannot be read, or the offsets cannot be resolved, the step
     * is skipped. Categorisation prefers explicit prefixes in the location's
     * message text ("Source:", "Sink:", "Propagator:") and falls back to position
     * (first → SOURCE, last → SINK, middle → PROPAGATOR) when the message doesn't
     * carry a tag.
     */
    private fun resolveSarifDataFlow(
        project: Project,
        result: Result,
        fileContentCache: MutableMap<String, String?>,
    ): List<DataFlowElement> {
        val rawLocations = result.codeFlows
            ?.firstOrNull()
            ?.threadFlows
            ?.firstOrNull()
            ?.locations
            ?.mapNotNull { it.location }
            .orEmpty()
        if (rawLocations.isEmpty()) return emptyList()

        val projectPath = project.basePath ?: return emptyList()
        val out = mutableListOf<DataFlowElement>()
        val total = rawLocations.size

        rawLocations.forEachIndexed { index, location ->
            val phys = location.physicalLocation ?: return@forEachIndexed
            val uri = phys.artifactLocation?.uri ?: return@forEachIndexed
            val region = phys.region ?: return@forEachIndexed
            val startLine = region.startLine ?: return@forEachIndexed
            val endLine = region.endLine ?: startLine
            val startCol = region.startColumn ?: 1
            val endCol = region.endColumn ?: (startCol + 1)

            val content = fileContentCache.getOrPut(uri) {
                runCatching { File(projectPath, uri).readText() }.getOrNull()
            } ?: return@forEachIndexed

            val startOffset = lineColToOffset(content, startLine, startCol) ?: return@forEachIndexed
            val endOffset = lineColToOffset(content, endLine, endCol) ?: return@forEachIndexed
            if (endOffset <= startOffset) return@forEachIndexed

            val name = region.snippet?.text
                ?: location.message?.text
                ?: "step ${index + 1}"
            val category = classifyTraceStep(location.message?.text, index, total)
            out.add(DataFlowElement(name, startOffset, endOffset, category))
        }
        return out
    }

    /**
     * Maps a 1-based (line, column) pair to a 0-based character offset within
     * [content]. Returns null when the line is out of range. Robust to both LF
     * and CRLF line separators by counting `\n` and treating any preceding `\r`
     * as part of the same line break.
     */
    private fun lineColToOffset(content: String, line: Int, column: Int): Int? {
        if (line < 1 || column < 1) return null
        var currentLine = 1
        var index = 0
        while (currentLine < line && index < content.length) {
            if (content[index] == '\n') currentLine++
            index++
        }
        if (currentLine != line) return null
        // Add the (column - 1) chars on this line, but stay within the line's bounds.
        var col = 1
        while (col < column && index < content.length && content[index] != '\n') {
            index++
            col++
        }
        return index
    }

    private fun classifyTraceStep(messageText: String?, index: Int, total: Int): DataFlowCategory {
        val msg = messageText?.trimStart()?.lowercase().orEmpty()
        return when {
            msg.startsWith("source") -> DataFlowCategory.SOURCE
            msg.startsWith("sink") -> DataFlowCategory.SINK
            msg.startsWith("propagator") -> DataFlowCategory.PROPAGATOR
            index == 0 -> DataFlowCategory.SOURCE
            index == total - 1 -> DataFlowCategory.SINK
            else -> DataFlowCategory.PROPAGATOR
        }
    }
}
