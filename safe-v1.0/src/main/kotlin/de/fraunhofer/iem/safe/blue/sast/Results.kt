package de.fraunhofer.iem.safe.blue.sast

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

data class IssueLocation(
    val fileName: String,
    val codeSnippet: String,
    val startLine: Int,
    val endLine: Int,
) {
    override fun toString(): String {
        return fileName
    }
}

data class Issue(
    val type: String,
    val message: String,
    val tags: List<String>,
    val location: IssueLocation,
    var explanation: String? = "",
    val confidence: String? = null,
    val severity: String? = null,
    val cwe: List<String>? = null,
    val owasp: List<String>? = null,
    val impact: String? = null,
    val hasDataFlowTrace: Boolean,
    val dataFlowTrace: List<DataFlowElement>?,
) {
    override fun toString(): String {
        return type
    }
}

data class DataFlowElement(
    val name: String,
    val startOffset: Int,
    val endOffset: Int,
    val type: DataFlowCategory
)

enum class DataFlowCategory {
    SOURCE, PROPAGATOR, SINK
}

