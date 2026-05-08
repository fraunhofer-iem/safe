package de.fraunhofer.iem.safe.blue.llm

data class Explanation(
    val overview: String,
    val explanation: String,
    val exampleCode: String,
    val exampleCodeExplanation: String
)
