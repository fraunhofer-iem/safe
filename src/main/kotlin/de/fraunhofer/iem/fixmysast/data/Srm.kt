package de.fraunhofer.iem.fixmysast.data

data class Srm(
    val signature: String,
    val srm: List<String>,
    val cwe: List<String>
)