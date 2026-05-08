package de.fraunhofer.iem.safe.blue.study

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Per-project JSONL writer for study-mode telemetry. Each event is one line:
 *
 * ```json
 * {"ts":"2026-05-08T10:14:21.412Z","plugin":"safe","participant_id":"P07",
 *  "session_id":"…","finding_id":"…","event":"finding.selected","data":{…}}
 * ```
 *
 * Behaviour mirrors the new plugin's recorder: lazy file creation, 5-event
 * flush window, no-op when [StudyModeSettings.enabled] is false. The first
 * write in a session emits a `session.started` envelope ahead of the requested
 * event; `dispose()` closes with `session.ended` if anything was ever written.
 *
 * Distinguished from the new plugin's recorder by `pluginId = "safe"` so a
 * single analysis pipeline can mix logs from both plugin variants.
 */
@Service(Service.Level.PROJECT)
class TelemetryRecorder(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(TelemetryRecorder::class.java)
    private val mapper = jacksonObjectMapper()

    private val pluginId: String = "safe"

    @Volatile private var writer: BufferedWriter? = null
    private var pendingFlush = 0

    @Volatile private var sessionStartedAtMs: Long = 0L

    init {
        Disposer.register(project, this)
    }

    fun record(event: String, findingId: String? = null, data: Map<String, Any?> = emptyMap()) {
        val settings = StudyModeSettings.getInstance()
        if (!settings.enabled) return
        try {
            val w = ensureWriter() ?: return
            if (sessionStartedAtMs == 0L && event != "session.started") {
                sessionStartedAtMs = System.currentTimeMillis()
                writeEvent(w, settings, "session.started", null, sessionStartedData())
            } else if (sessionStartedAtMs == 0L && event == "session.started") {
                sessionStartedAtMs = System.currentTimeMillis()
            }
            writeEvent(w, settings, event, findingId, data)
        } catch (e: Exception) {
            logger.warn("Telemetry write failed for event '$event'", e)
        }
    }

    private fun writeEvent(
        w: BufferedWriter,
        settings: StudyModeSettings,
        event: String,
        findingId: String?,
        data: Map<String, Any?>,
    ) {
        val obj = linkedMapOf<String, Any?>(
            "ts" to Instant.now().toString(),
            "plugin" to pluginId,
            "participant_id" to settings.participantId,
            "session_id" to settings.sessionId,
        )
        if (findingId != null) obj["finding_id"] = findingId
        obj["event"] = event
        if (data.isNotEmpty()) obj["data"] = data

        synchronized(this) {
            w.write(mapper.writeValueAsString(obj))
            w.write("\n")
            pendingFlush++
            if (pendingFlush >= 5) {
                w.flush()
                pendingFlush = 0
            }
        }
    }

    private fun sessionStartedData(): Map<String, Any?> {
        // v1's LLM provider is single — read its platform tag if SafeSettings is wired up.
        val provider = runCatching {
            de.fraunhofer.iem.safe.blue.llm.SafeSettings.getInstance().platform.ifBlank { "default" }
        }.getOrNull()
        return mapOf(
            "project_name" to project.name,
            "project_path" to project.basePath,
            "active_provider" to provider,
        )
    }

    fun currentFile(): Path? = currentPath
    @Volatile private var currentPath: Path? = null

    private fun ensureWriter(): BufferedWriter? {
        writer?.let { return it }
        synchronized(this) {
            writer?.let { return it }
            val settings = StudyModeSettings.getInstance()
            return try {
                val dir = telemetryDirectory(settings)
                Files.createDirectories(dir)
                val safeParticipant = settings.participantId.ifBlank { "anonymous" }
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")
                val fileName = "${safeParticipant}__${settings.sessionId}__$pluginId.jsonl"
                val path = dir.resolve(fileName)
                val w = Files.newBufferedWriter(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                writer = w
                currentPath = path
                w
            } catch (e: Exception) {
                logger.warn("Could not open telemetry file in ${settings.telemetryDir.ifBlank { "<default>" }}", e)
                null
            }
        }
    }

    private fun telemetryDirectory(settings: StudyModeSettings): Path {
        val configured = settings.telemetryDir
        if (configured.isNotBlank()) return Path.of(configured)
        val basePath = project.basePath ?: return Path.of(System.getProperty("user.home"), ".safe-telemetry")
        return Path.of(basePath, ".idea", "safe-telemetry")
    }

    override fun dispose() {
        if (sessionStartedAtMs > 0L) {
            val w = writer
            if (w != null) {
                try {
                    writeEvent(
                        w,
                        StudyModeSettings.getInstance(),
                        "session.ended",
                        findingId = null,
                        data = mapOf(
                            "duration_ms" to (System.currentTimeMillis() - sessionStartedAtMs)
                                .coerceAtLeast(0L),
                        ),
                    )
                } catch (_: Exception) { }
            }
        }
        synchronized(this) {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) { }
            writer = null
            currentPath = null
            pendingFlush = 0
            sessionStartedAtMs = 0L
        }
    }

    companion object {
        fun getInstance(project: Project): TelemetryRecorder = project.service()
    }
}
