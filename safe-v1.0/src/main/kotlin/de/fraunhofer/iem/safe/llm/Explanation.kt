package de.fraunhofer.iem.safe.llm

data class Explanation(
    val overview: String,
    val explanation: String,
    val exampleCode: String,
    val exampleCodeExplanation: String
)
