package de.fraunhofer.iem.fixmysast.ui

data class ExplanationModel(
    val explanation: String,
    val exampleCode: String,
    val exampleCodeExplanation: String,
    val codeFixSuggestion: String,
    val codeSectionExplanation: String
)
