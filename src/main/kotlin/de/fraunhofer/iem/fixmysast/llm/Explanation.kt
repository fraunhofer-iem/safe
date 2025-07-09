package de.fraunhofer.iem.fixmysast.llm

data class Explanation(
    val explanation: String,
    val exampleCode: String,
    val exampleCodeExplanation: String,
    val codeFixSuggestion: String,
    val codeSectionExplanation: String
)
