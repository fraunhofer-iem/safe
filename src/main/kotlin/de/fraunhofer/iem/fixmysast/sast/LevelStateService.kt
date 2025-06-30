package de.fraunhofer.iem.fixmysast.sast

import com.intellij.ide.util.PropertiesComponent
object LevelStateService {
    private const val KEY = "FixMySAST.Level"
    private val props: PropertiesComponent = PropertiesComponent.getInstance()

    var current: ExpertiseLevel
        get() = ExpertiseLevel.valueOf(props.getValue(KEY, ExpertiseLevel.BEGINNER.name))
        set(value) { props.setValue(KEY, value.name) }
}