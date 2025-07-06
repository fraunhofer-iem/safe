package de.fraunhofer.iem.fixmysast.sast.dataModel

/**
 * Data classes for the SAST issues, results, and expertise level
 *
 * @author Alexandra Fomina
 */
data class SASTIssue(
    val type: String,
    val message: String,
    val tags: List<String>,
    val codeSnippet: String
)

data class SASTResult(
    val groupedIssues: Map<String, List<SASTIssue>>
)

enum class ExpertiseLevel(val label: String) {
    BEGINNER("Beginner"),
    INTERMEDIATE("Intermediate"),
    ADVANCED("Advanced");

    override fun toString() = label
}