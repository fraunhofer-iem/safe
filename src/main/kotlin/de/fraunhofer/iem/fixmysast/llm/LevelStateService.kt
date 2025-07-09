package de.fraunhofer.iem.fixmysast.llm

import com.intellij.openapi.components.*

@Service(Service.Level.APP)
@State(
    name = "FixMySAST.level",
    storages = [Storage(StoragePathMacros.NON_ROAMABLE_FILE)]
)
class LevelStateService : PersistentStateComponent<LevelStateService.State> {
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
}

enum class ExpertiseLevel(val label: String) {
    BEGINNER("Beginner"),
    INTERMEDIATE("Intermediate"),
    ADVANCED("Advanced");

    override fun toString() = label
}