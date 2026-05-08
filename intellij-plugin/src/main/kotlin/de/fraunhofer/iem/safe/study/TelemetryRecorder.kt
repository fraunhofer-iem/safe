package de.fraunhofer.iem.safe.study

import com.google.gson.Gson
import com.google.gson.JsonObject
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
 * {"ts":"2026-05-08T10:14:21.412Z","plugin":"safer","participant_id":"P07",
 *  "session_id":"…","finding_id":"…","event":"deepdive.opened","data":{…}}
 * ```
 *
 * Behaviour:
 *  - When [StudyModeSettings.enabled] is `false`, [record] is a no-op — call
 *    sites can be unconditional without paying any cost in normal use.
 *  - The output file is opened lazily on first event so a participant who
 *    enables study mode mid-session doesn't get an empty stub file.
 *  - Writes are flushed every 5 events and on dispose; an IDE crash may lose
 *    up to 4 events but never corrupts the JSONL.
 *  - Lifetime is tied to the project — closing the project flushes and closes.
 */
@Service(Service.Level.PROJECT)
class TelemetryRecorder(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(TelemetryRecorder::class.java)
    private val gson = Gson()

    /** "safer" for the new plugin, "safe" for v1. Overridable for v1's port. */
    private val pluginId: String = "safer"

    @Volatile private var writer: BufferedWriter? = null
    private var pendingFlush = 0

    /** Set on the first event in this session (millis). 0 means no events yet. */
    @Volatile private var sessionStartedAtMs: Long = 0L

    init {
        Disposer.register(project, this)
    }

    fun record(event: String, findingId: String? = null, data: Map<String, Any?> = emptyMap()) {
        val settings = StudyModeSettings.getInstance()
        if (!settings.enabled) return
        try {
            val w = ensureWriter() ?: return
            // First write in this session — emit `session.started` ahead of the
            // requested event so the JSONL always opens with a session marker.
            // The compareAndSet semantics (Volatile + synchronized below)
            // guarantee at-most-once.
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
        val obj = JsonObject().apply {
            addProperty("ts", Instant.now().toString())
            addProperty("plugin", pluginId)
            addProperty("participant_id", settings.participantId)
            addProperty("session_id", settings.sessionId)
            if (findingId != null) addProperty("finding_id", findingId)
            addProperty("event", event)
            if (data.isNotEmpty()) {
                add("data", gson.toJsonTree(data))
            }
        }
        synchronized(this) {
            w.write(gson.toJson(obj))
            w.write("\n")
            pendingFlush++
            if (pendingFlush >= 5) {
                w.flush()
                pendingFlush = 0
            }
        }
    }

    private fun sessionStartedData(): Map<String, Any?> {
        val provider = runCatching {
            de.fraunhofer.iem.safe.llm.SafeLlmSettings.getInstance().providerKind.id
        }.getOrNull()
        return mapOf(
            "project_name" to project.name,
            "project_path" to project.basePath,
            "active_provider" to provider,
        )
    }

    /** Returns the absolute path of the active telemetry file, or `null` if nothing has been written. */
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
        // Bracket the JSONL with `session.ended` if anything ever wrote to it
        // in this session — gives analysis a clean termination marker without
        // having to hunt for the last event of a session.
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
                } catch (_: Exception) { /* swallow — IDE shutdown path */ }
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
