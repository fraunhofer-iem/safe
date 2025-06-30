package de.fraunhofer.iem.fixmysast.sast

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ide.util.PropertiesComponent

@Service(Service.Level.APP)
class LevelStateService {

    var current: ExpertiseLevel = ExpertiseLevel.BEGINNER
        set(value) {
            if (field == value) return
                field = value
                listeners.forEach { it(value) }
            }

    private val listeners = mutableSetOf<(ExpertiseLevel) -> Unit>()

    fun addListener(listener: (ExpertiseLevel) -> Unit) {
        listeners.add(listener)
    }
    fun removeListener(listener: (ExpertiseLevel) -> Unit) {
        listeners.remove(listener)
    }

    companion object {
        fun get() = service<LevelStateService>()
    }
//    private const val KEY = "FixMySAST.Level"
//    private val props: PropertiesComponent = PropertiesComponent.getInstance()
//
//    var current: ExpertiseLevel
//        get() = ExpertiseLevel.valueOf(props.getValue(KEY, ExpertiseLevel.BEGINNER.name))
//        set(value) { props.setValue(KEY, value.name) }
}