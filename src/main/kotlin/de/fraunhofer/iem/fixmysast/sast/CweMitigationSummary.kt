package de.fraunhofer.iem.fixmysast.sast

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Loads the cwe mitigation summary from the resource directory and provides the functionality to get the mitigation summary in HTML format for the given CWE ID
 *
 * @author Ranjith
 */
object CweMitigationSummary {
    private var mitigationSummary: Map<String, Any>? = null;

    private fun getMitigationSummary(): Map<String, Any>? {
        if (mitigationSummary == null) {
            val mapper = jacksonObjectMapper()

            val res: Map<String, Any>? = object {}.javaClass.classLoader.getResourceAsStream("mitigation_summary_based_on_strategy.json")
                ?.let { mapper.readValue(it) }

            if (res != null) {
                mitigationSummary = res
            }
        }

        return mitigationSummary
    }

    fun getMitigationSummaryFor(cweId: String): String {
        val summaryInfo = getMitigationSummary() ?: return "Currently Summary not available"

        if (summaryInfo.containsKey(cweId)) {
            val summary = (summaryInfo[cweId] as Map<String, Any>)["MitigationSummary"] as Map<String, List<String>>

            val summaryText = StringBuilder("")

            for ((strategy, strategySummaries) in summary) {
                if (strategySummaries.isNotEmpty()) {
                    summaryText.append("<h3>$strategy</h3>\n")
                    summaryText.append("<ul>\n")

                    for (strategySummary in (strategySummaries as List<Map<String, Any>>)) {
                        val desc = strategySummary["Description"] as String
                        val phases = (strategySummary["Phases"] as List<String>).joinToString(", ")

                        summaryText.append("\t<li>$desc Phases: $phases</li>\n")
                    }

                    summaryText.append("</ul>\n\n")
                }
            }

            return summaryText.toString()
        }

        return "Currently Summary not available"
    }
}