package de.fraunhofer.iem.safe.study

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.util.UUID

/**
 * Application-level "study mode" toggle and identity. When `enabled` is true the
 * plugin records interaction telemetry to a JSONL file via [TelemetryRecorder]
 * and exposes the *Pre-warm Explanations* action.
 *
 * Persisted fields: participant id supplied by the experimenter, telemetry
 * directory (defaults to `<project>/.idea/safe-telemetry/`) and the on/off
 * flag. The session id rolls every IDE start so a participant who restarts
 * mid-study produces two distinguishable sessions.
 */
@Service(Service.Level.APP)
@State(name = "SafeStudyMode-Blue", storages = [Storage("safeStudyMode-blue.xml")])
class StudyModeSettings : PersistentStateComponent<StudyModeSettings.State> {

    class State {
        var enabled: Boolean = false
        var participantId: String = ""
        /** Empty ⇒ default to `<project>/.idea/safe-telemetry/`. */
        var telemetryDir: String = ""
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    var enabled: Boolean
        get() = myState.enabled
        set(value) { myState.enabled = value }

    var participantId: String
        get() = myState.participantId
        set(value) { myState.participantId = value }

    var telemetryDir: String
        get() = myState.telemetryDir
        set(value) { myState.telemetryDir = value }

    val sessionId: String by lazy { UUID.randomUUID().toString().take(12) }

    companion object {
        fun getInstance(): StudyModeSettings = ApplicationManager.getApplication().service()
    }
}
