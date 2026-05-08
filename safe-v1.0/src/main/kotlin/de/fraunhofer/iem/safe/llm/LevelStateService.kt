package de.fraunhofer.iem.safe.llm

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.APP)

class LevelStateService {
    private val listeners = mutableListOf<(Int) -> Unit>()
    private var _current: Int = 5

    var current: Int
        get() = _current.toInt()
        set(value) {
            if(_current == value) return
                _current = value
            listeners.forEach { it(value) }

            }

    fun load(project: Project) {
        val stored = PropertiesComponent.getInstance(project)
            .getInt("de.fraunhofer.iem.safe.expertiseValue", 5)
        current = stored
    }

    fun save(project: Project) {
        PropertiesComponent.getInstance(project)
            .setValue("de.fraunhofer.iem.safe.expertiseValue", _current.toString())
    }

    fun addListener(listener: (Int) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Int) -> Unit) {
        listeners.remove(listener)
    }

    companion object {
        fun get(): LevelStateService = service()
    }

}

typealias ExpertiseLevel = String
//enum class ExpertiseLevel(val label: String) {
//    BEGINNER("Beginner"),
//    INTERMEDIATE("Intermediate"),
//    ADVANCED("Advanced");

//    override fun toString() = label
//}