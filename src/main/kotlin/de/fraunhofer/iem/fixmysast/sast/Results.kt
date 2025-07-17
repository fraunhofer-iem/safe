package de.fraunhofer.iem.fixmysast.sast

/**
 * Data classes for the SAST issues, results, and expertise level
 *
 * @author Alexandra Fomina
 */
data class Results(
    val filePath: String,
    val projectPath: String,
    val tool: String,
    val issues: List<Issue>
)

data class Issue(
    val type: String,
    val message: String,
    val tags: List<String>,
    val hasDataFlowTrace: Boolean,
    val dataFlowTrace: List<DataFlowElement>?,
    val path: String?,
    //Add line numbers
    val startLine: Int?,
    val endLine: Int?,
    val codeSnippet: String,
    var explanation: String = "",
)

