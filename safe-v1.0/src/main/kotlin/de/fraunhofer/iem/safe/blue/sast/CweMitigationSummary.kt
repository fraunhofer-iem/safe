package de.fraunhofer.iem.safe.blue.sast

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.collections.iterator

/**
 * Loads the cwe mitigation summary from the resource directory and provides the functionality to get the mitigation summary in HTML format for the given CWE ID
 *
 * @author Ranjith
 */
object CweMitigationSummary {
    private var mitigationSummary: Map<String, Any>? = null

    /**
     * Study-time fallback table for CWEs that aren't in
     * `mitigation_summary_based_on_strategy.json`. Each entry maps a missing
     * CWE id to a near-equivalent that is in the catalogue — usually the
     * MITRE-defined parent. Child CWEs typically share the parent's
     * mitigations, so reusing them is more useful than the "Summary not
     * available" placeholder. Less precise than per-child entries, but the
     * gap is filled automatically.
     *
     * When the JSON gains a per-child entry, drop the corresponding row here.
     */
    private val parentCwe: Map<String, String> = mapOf(
        // Path-traversal family → CWE-22 (Improper Limitation of a Pathname to a Restricted Directory)
        "23" to "22",  // Relative Path Traversal
        "36" to "22",  // Absolute Path Traversal
        // XSS family → CWE-79 (Improper Neutralization of Input During Web Page Generation)
        "80" to "79",  // Basic XSS
        "81" to "79",  // Improper Neutralization of Script in Error Messages
        "86" to "79",  // Improper Neutralization of Invalid Characters in Identifiers
        // Crypto family → CWE-326 (Inadequate Encryption Strength)
        "327" to "326", // Use of a Broken or Risky Cryptographic Algorithm
        "329" to "326", // Generation of Predictable IV with CBC Mode
        "916" to "326", // Use of Password Hash With Insufficient Computational Effort
    )

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

        // Direct hit first; if missing, fall back to the parent CWE so child
        // CWEs (e.g. CWE-23 Relative Path Traversal → CWE-22) still surface
        // useful strategies instead of the placeholder.
        val resolvedKey = when {
            summaryInfo.containsKey(cweId) -> cweId
            parentCwe[cweId]?.let { summaryInfo.containsKey(it) } == true -> parentCwe[cweId]!!
            else -> return "Currently Summary not available"
        }

        @Suppress("UNCHECKED_CAST")
        val summary = (summaryInfo[resolvedKey] as Map<String, Any>)["MitigationSummary"] as Map<String, List<String>>

        val summaryText = StringBuilder("")

        for ((strategy, strategySummaries) in summary) {
            if (strategySummaries.isNotEmpty()) {
                summaryText.append("<h3>$strategy</h3>\n")
                summaryText.append("<ul>\n")

                @Suppress("UNCHECKED_CAST")
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
}
