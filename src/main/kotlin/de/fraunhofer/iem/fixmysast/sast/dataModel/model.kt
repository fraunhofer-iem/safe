package de.fraunhofer.iem.fixmysast.sast.dataModel

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