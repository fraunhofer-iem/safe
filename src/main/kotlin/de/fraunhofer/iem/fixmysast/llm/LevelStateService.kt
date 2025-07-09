package de.fraunhofer.iem.fixmysast.llm

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import de.fraunhofer.iem.fixmysast.llm.ExplainResults.ExpertiseLevel

@Service(Service.Level.APP)
@State(
    name = "FixMySAST.level",
    storages = [Storage(StoragePathMacros.NON_ROAMABLE_FILE)]
)
class LevelStateService: PersistentStateComponent<LevelStateService.State>
{
    data class State(
        var level: ExpertiseLevel = ExpertiseLevel.INTERMEDIATE
    )
    private var state = State()

    private val listeners = mutableListOf<(ExpertiseLevel) -> Unit>()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        _current = state.level
    }

    private var _current: ExpertiseLevel = state.level

    var current: ExpertiseLevel
        get() = _current
        set(value) {
            if (_current == value) return
            _current = value
            state.level = value
            listeners.forEach { it(value) }
        }

    fun addListener(listener: (ExpertiseLevel) -> Unit) {
        listeners.add(listener)
    }
    fun removeListener(listener: (ExpertiseLevel) -> Unit) {
        listeners.remove(listener)
    }

    companion object {
        fun get(): LevelStateService = service()
    }
//    private const val KEY = "FixMySAST.Level"
//    private val props: PropertiesComponent = PropertiesComponent.getInstance()
//
//    var current: ExpertiseLevel
//        get() = ExpertiseLevel.valueOf(props.getValue(KEY, ExpertiseLevel.BEGINNER.name))
//        set(value) { props.setValue(KEY, value.name) }
}