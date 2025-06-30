package de.fraunhofer.iem.fixmysast.sast

enum class ExpertiseLevel(val label: String) {
    BEGINNER("Beginner"),
    INTERMEDIATE("Intermediate"),
    ADVANCED("Advanced");

    override fun toString() = label
}
