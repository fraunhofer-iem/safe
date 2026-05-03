package de.fraunhofer.iem.safe.sast

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parses SARIF 2.1.0 files into [VulnerabilityInfo] records, one per result location.
 * Tolerant of schema variation: walks raw maps rather than binding to a typed model.
 */
object SarifParser {

    private val cwePattern = Regex("CWE-\\d+")

    fun parse(path: Path): List<VulnerabilityInfo> = Files.newInputStream(path).use { stream ->
        InputStreamReader(stream).use(::parse)
    }

    fun parse(reader: InputStreamReader): List<VulnerabilityInfo> {
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        val root: Map<String, Any?> = Gson().fromJson(reader, type) ?: return emptyList()
        val runs = root["runs"] as? List<*> ?: return emptyList()

        val results = mutableListOf<VulnerabilityInfo>()
        for (run in runs) {
            val runMap = run as? Map<*, *> ?: continue
            val ruleIndex = buildRuleIndex(runMap)
            val runResults = runMap["results"] as? List<*> ?: continue
            for (result in runResults) {
                val resultMap = result as? Map<*, *> ?: continue
                results += parseResult(resultMap, ruleIndex)
            }
        }
        return results
    }

    private fun buildRuleIndex(run: Map<*, *>): Map<String, Map<*, *>> {
        val driver = ((run["tool"] as? Map<*, *>)?.get("driver") as? Map<*, *>) ?: return emptyMap()
        val rules = driver["rules"] as? List<*> ?: return emptyMap()
        return rules.mapNotNull { rule ->
            val ruleMap = rule as? Map<*, *> ?: return@mapNotNull null
            val id = ruleMap["id"] as? String ?: return@mapNotNull null
            id to ruleMap
        }.toMap()
    }

    private fun parseResult(result: Map<*, *>, ruleIndex: Map<String, Map<*, *>>): List<VulnerabilityInfo> {
        val ruleId = result["ruleId"] as? String
        val message = ((result["message"] as? Map<*, *>)?.get("text") as? String)
        val severity = result["level"] as? String
        val cwe = resolveCwe(result, ruleIndex[ruleId], message, ruleId)
        val traces = parseCodeFlows(result["codeFlows"] as? List<*>)
        val ruleName = (ruleIndex[ruleId]?.get("name") as? String) ?: ruleId
        val locations = result["locations"] as? List<*> ?: return emptyList()

        if (locations.isEmpty()) {
            return listOf(
                VulnerabilityInfo(
                    inspectionId = ruleId,
                    inspectionName = ruleName,
                    severity = severity,
                    message = message,
                    cwe = cwe,
                    traces = traces,
                )
            )
        }

        return locations.mapNotNull { location ->
            val locationMap = location as? Map<*, *> ?: return@mapNotNull null
            val physical = locationMap["physicalLocation"] as? Map<*, *> ?: return@mapNotNull null
            val artifact = physical["artifactLocation"] as? Map<*, *>
            val region = physical["region"] as? Map<*, *>
            val snippetText = (region?.get("snippet") as? Map<*, *>)?.get("text") as? String

            VulnerabilityInfo(
                inspectionId = ruleId,
                inspectionName = ruleName,
                severity = severity,
                message = message,
                filePath = artifact?.get("uri") as? String,
                startLine = (region?.get("startLine") as? Number)?.toInt(),
                endLine = (region?.get("endLine") as? Number)?.toInt(),
                startColumn = (region?.get("startColumn") as? Number)?.toInt(),
                endColumn = (region?.get("endColumn") as? Number)?.toInt(),
                snippet = snippetText,
                cwe = cwe,
                traces = traces,
            )
        }
    }

    private fun parseCodeFlows(codeFlows: List<*>?): List<TaintTrace> {
        if (codeFlows.isNullOrEmpty()) return emptyList()
        val traces = mutableListOf<TaintTrace>()
        for (codeFlow in codeFlows) {
            val codeFlowMap = codeFlow as? Map<*, *> ?: continue
            val flowMessage = (codeFlowMap["message"] as? Map<*, *>)?.get("text") as? String
            val threadFlows = codeFlowMap["threadFlows"] as? List<*> ?: continue
            for (threadFlow in threadFlows) {
                val tfMap = threadFlow as? Map<*, *> ?: continue
                val tfMessage = (tfMap["message"] as? Map<*, *>)?.get("text") as? String ?: flowMessage
                val tfLocations = tfMap["locations"] as? List<*> ?: continue
                val steps = tfLocations.mapNotNull { tfLoc ->
                    val tfLocMap = tfLoc as? Map<*, *> ?: return@mapNotNull null
                    val location = tfLocMap["location"] as? Map<*, *> ?: return@mapNotNull null
                    val physical = location["physicalLocation"] as? Map<*, *> ?: return@mapNotNull null
                    val artifact = physical["artifactLocation"] as? Map<*, *>
                    val region = physical["region"] as? Map<*, *>
                    val locationMessage = (location["message"] as? Map<*, *>)?.get("text") as? String
                    TaintStep(
                        filePath = artifact?.get("uri") as? String,
                        startLine = (region?.get("startLine") as? Number)?.toInt(),
                        startColumn = (region?.get("startColumn") as? Number)?.toInt(),
                        endLine = (region?.get("endLine") as? Number)?.toInt(),
                        endColumn = (region?.get("endColumn") as? Number)?.toInt(),
                        message = locationMessage,
                    )
                }
                if (steps.isNotEmpty()) traces += TaintTrace(tfMessage, steps)
            }
        }
        return traces
    }

    private fun resolveCwe(
        result: Map<*, *>,
        rule: Map<*, *>?,
        message: String?,
        ruleId: String?,
    ): Cwe? {
        cweFromTags(result["properties"] as? Map<*, *>)?.let { return it }
        cweFromTags(rule?.get("properties") as? Map<*, *>)?.let { return it }
        cweFromTags(rule?.get("defaultProperties") as? Map<*, *>)?.let { return it }
        if (message != null) cwePattern.find(message)?.value?.let { return QodanaNodeExtractor.cweFromTagString(it) }
        return QodanaNodeExtractor.inspectionIdToCwe(ruleId)
    }

    private fun cweFromTags(properties: Map<*, *>?): Cwe? {
        if (properties == null) return null
        val tags = properties["tags"] as? List<*> ?: return null
        val tag = tags.firstOrNull { (it as? String)?.startsWith("CWE-") == true } as? String
        return tag?.let(QodanaNodeExtractor::cweFromTagString)
    }
}
