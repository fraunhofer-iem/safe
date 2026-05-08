package de.fraunhofer.iem.safe.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import de.fraunhofer.iem.safe.sast.VulnerabilityInfo
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Persists the most recent set of imported findings (per project) so the SAFE plugin can
 * restore them on the next IDE start without re-running an import. The snapshot stores:
 *   - the source (Qodana plugin or a SARIF file),
 *   - the SARIF path (only when [Source.SARIF]),
 *   - the findings encoded as JSON,
 *   - a fingerprint hash that the panel uses to detect when a Qodana run has produced
 *     different results than the last snapshot.
 */
@Service(Service.Level.PROJECT)
@State(name = "SafeFindingsSnapshot-Red", storages = [Storage("safeFindings-red.xml")])
class FindingsSnapshotService : PersistentStateComponent<FindingsSnapshotService.State> {

    enum class Source { SARIF, QODANA }

    class State {
        var source: String? = null
        var sarifPath: String? = null
        var findingsJson: String? = null
        var fingerprint: String? = null
        /** Last leaf selected in the SAFE tree, encoded as `inspectionId::filePath`. Restored on next IDE start. */
        var lastSelectedKey: String? = null
    }

    private val logger = Logger.getInstance(FindingsSnapshotService::class.java)
    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    val source: Source?
        get() = myState.source?.let { runCatching { Source.valueOf(it) }.getOrNull() }

    val fingerprint: String? get() = myState.fingerprint

    val sarifPath: String? get() = myState.sarifPath

    var lastSelectedKey: String?
        get() = myState.lastSelectedKey
        set(value) { myState.lastSelectedKey = value }

    fun save(source: Source, findings: List<VulnerabilityInfo>, sarifPath: String? = null) {
        myState.source = source.name
        myState.sarifPath = sarifPath
        myState.findingsJson = Gson().toJson(findings)
        myState.fingerprint = fingerprintOf(findings)
    }

    fun loadFindings(): List<VulnerabilityInfo>? {
        val json = myState.findingsJson ?: return null
        return try {
            val type = object : TypeToken<List<VulnerabilityInfo>>() {}.type
            Gson().fromJson(json, type)
        } catch (ex: Exception) {
            logger.warn("Failed to deserialize findings snapshot; starting fresh.", ex)
            null
        }
    }

    companion object {
        fun getInstance(project: Project): FindingsSnapshotService = project.service()

        /**
         * Stable hash over the identifying fields of each finding. Two finding lists produce
         * the same fingerprint iff they contain the same logical issues (irrespective of order).
         */
        fun fingerprintOf(findings: List<VulnerabilityInfo>): String {
            val signature = findings
                .asSequence()
                .map {
                    listOf(it.inspectionId, it.filePath, it.startLine, it.endLine, it.startColumn, it.endColumn, it.message)
                        .joinToString("|") { f -> f?.toString() ?: "" }
                }
                .sorted()
                .joinToString("\n")
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
