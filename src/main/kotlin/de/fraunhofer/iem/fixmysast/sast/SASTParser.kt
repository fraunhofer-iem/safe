package de.fraunhofer.iem.fixmysast.sast
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.project.Project
import java.io.File
import com.intellij.openapi.diagnostic.Logger
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.jetbrains.rd.util.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.Executors

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
    val locations: List<Location>?
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Message(val text: String)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Location(val physicalLocation: PhysicalLocation?)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PhysicalLocation(val artifactLocation: ArtifactLocation?, val region: Region?)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ArtifactLocation(val uri: String?)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Region(val startLine:Int?, val endLine:Int?)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Rule (val id:String?, val properties: JsonNode? = null)

//Output models
data class SASTIssue(val type: String, val message: String, val tags: List<String>, val codeSnippet: String)
data class SASTResult(val groupedIssues: Map<String, List<SASTIssue>>)

//field to hold LLM responses
data class SASTParsedResult(
    val groupedIssues: Map<String, List<SASTIssue>>,
    val llmExplanations: Map<SASTIssue, String>
)
private val logger = Logger.getInstance("FixMySAST")

object SASTParser {
//C:\Users\admin\Downloads\BenchmarkJava-master\BenchmarkJava-master\results\Benchmark_1.2-Semgrep-v1.71.0.sarif
    // C:\Users\admin\Downloads\BenchmarkJava-master\results\Benchmark_1.2-Semgrep-v1.71.0.sarif
    fun parseSarifFromProject(project: Project): de.fraunhofer.iem.fixmysast.sast.SASTParsedResult {
        val projectPath = project.basePath ?: return fallbackWithLLM(parseSarifFileFromResourceStream(project))
        val sarifFile = File(projectPath, "results" + File.separator + "Benchmark_1.2-Semgrep-v1.123.0_Edited.sarif")

        return try {
            if (sarifFile.exists()) {
                val sarifContent = sarifFile.readText()
                val mapper = jacksonObjectMapper()
                val sarifReport: de.fraunhofer.iem.fixmysast.sast.SarifReport = mapper.readValue(sarifContent)

                val issues = mutableListOf<de.fraunhofer.iem.fixmysast.sast.SASTIssue>()
                sarifReport.runs.forEach { run ->
                    run.results.forEach { result ->
                        val type = result.ruleId ?: "Unknown"
                        val message = result.message.text
                        val tags = run.tool.driver.rules?.find { it.id == result.ruleId} ?.properties?.get("tags")?.mapNotNull { it.asText() } ?: emptyList<String>()
                        val codeSnippet = extractCodeSnippet(project,result)
                        issues.add(SASTIssue(type, message, tags, codeSnippet))
                    }
                }

                val grouped = issues.groupBy {it.type}
                val explanationPipe = ConcurrentHashMap<de.fraunhofer.iem.fixmysast.sast.SASTIssue, String>()

                val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                val scope = CoroutineScope(dispatcher)

                issues.forEach { issue ->
                    scope.launch {
                        try {
                            val res = LLMClient.getExplanation(issue)
                            explanationPipe[issue] = res?:"LLM failed to generate explanation"
                        } catch (e: Exception) {
                            explanationPipe[issue] = "LLM failed to generate explanation horribly"
                        }
                    }
                }

                SASTParsedResult(grouped, explanationPipe)
            } else {
                println("SARIF file not found at ${sarifFile.absolutePath}")
                de.fraunhofer.iem.fixmysast.sast.logger.warn("Sarif file not found at ${sarifFile.absolutePath}")
                fallbackWithLLM(parseSarifFileFromResourceStream(project))
            }
        } catch (e: Exception) {
            println("Error parsing SARIF from project: ${e.message}")
            de.fraunhofer.iem.fixmysast.sast.logger.error(e.message)
            fallbackWithLLM(parseSarifFileFromResourceStream(project))
        }
    }

    fun parseSarifFileFromResourceStream(project:Project,fileName: String = "/results/Benchmark_1.2-Semgrep-v1.123.0_Edited.sarif"): de.fraunhofer.iem.fixmysast.sast.SASTResult {
        val inputStream = File(File(project.basePath), "/results/Benchmark_1.2-Semgrep-v1.123.0_Edited.sarif").inputStream()
        val mapper = jacksonObjectMapper()
        val sarifReport: SarifReport = mapper.readValue(inputStream)
        val issues = mutableListOf<de.fraunhofer.iem.fixmysast.sast.SASTIssue>()
        sarifReport.runs.forEach { run ->
            run.results.forEach { result ->
                val type = result.ruleId ?: "Unknown"
                val message = result.message.text
                val tags = result.properties?.get("tags")?.mapNotNull {it.asText() } ?: emptyList()
                val codeSnippet = extractCodeSnippet(project,result)
                issues.add(SASTIssue(type, message, tags, codeSnippet))
            }
        }
        return SASTResult(issues.groupBy { it.type }) }


    private fun extractCodeSnippet(project: Project, result: de.fraunhofer.iem.fixmysast.sast.Result): String {
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

    private fun fallbackWithLLM(fallback: de.fraunhofer.iem.fixmysast.sast.SASTResult): de.fraunhofer.iem.fixmysast.sast.SASTParsedResult {
        val explanations = fallback.groupedIssues.values.flatten().associateWith { issue ->
            LLMClient.getExplanation(issue) ?: "LLM failed to generate explanation"
        }
        return SASTParsedResult(fallback.groupedIssues, explanations)
    }
}
