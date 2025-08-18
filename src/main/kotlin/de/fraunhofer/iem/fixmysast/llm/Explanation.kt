package de.fraunhofer.iem.fixmysast.llm

data class Explanation(
    val overview: String,
    val explanation: String,
    val exampleCode: String,
    val exampleCodeExplanation: String
)
