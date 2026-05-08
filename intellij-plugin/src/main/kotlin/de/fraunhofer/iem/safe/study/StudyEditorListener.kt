package de.fraunhofer.iem.safe.study

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import de.fraunhofer.iem.safe.sast.VulnerabilityInfo
import de.fraunhofer.iem.safe.ui.ExplanationStorageService

/**
 * Records study-mode editor telemetry: file opens / closes, the active-file
 * shuffle as the participant moves between tabs, when the caret enters and
 * leaves a finding's line range, and net character add / remove counts per
 * file (debounced to one event per ~500 ms window so a participant typing
 * fast doesn't produce a hundred lines per second).
 *
 * Subscribes to the project's `FileEditorManager` for open / close / active-
 * file events, and walks every currently-open editor to attach `CaretListener`
 * and `DocumentListener` instances. New editors are caught when `selectionChanged`
 * fires the first time the user lands on them.
 *
 * The whole thing is bound to the project's `Disposable`, so closing the
 * project cleans up every registered listener and pending alarm.
 */
class StudyEditorListener(private val project: Project) : Disposable {

    private val recorder: TelemetryRecorder = TelemetryRecorder.getInstance(project)
    private val storage: ExplanationStorageService = ExplanationStorageService.getInstance(project)

    /** When was each file first opened (or selection-detected), in ms. */
    private val openedAt: MutableMap<String, Long> = mutableMapOf()
    /** Active-file → millis-since-last-switch base. */
    @Volatile private var activeFile: VirtualFile? = null
    @Volatile private var activeFileSinceMs: Long = 0L
    /** Per-file accumulator for `editor.modified` debouncing. */
    private val pendingMods: MutableMap<String, ModAccumulator> = mutableMapOf()
    /** Caret-in-finding tracking, keyed by file path. */
    private val caretInside: MutableMap<String, FindingId> = mutableMapOf()
    private val modAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Tracked separately from openedAt because it follows tool-window visibility, not editor lifecycle. */
    @Volatile private var safePanelVisible: Boolean = false
    @Volatile private var safePanelVisibleSinceMs: Long = 0L

    /**
     * Documents we've already attached caret / change listeners to.
     * Declared *before* the `init` block — Kotlin initialises class fields in
     * lexical order, and `init` calls `ensureListenersAttached(...)` which
     * dereferences this set. A late declaration left it null at that point
     * and produced an `NPE` on project open.
     */
    private val attachedDocs: MutableSet<Document> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    init {
        Disposer.register(project, this)

        val bus = project.messageBus.connect(this)
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                onFileOpened(file)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                onFileClosed(file)
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                onActiveFileChanged(event.oldFile, event.newFile)
                event.newFile?.let { ensureListenersAttached(it) }
            }
        })

        // Tool-window visibility — emits `panel.opened` / `panel.closed`/`panel.dwell`
        // for the SAFE tool window. A tool window's "available" state is tracked by
        // the `available` flag on stateChanged events; we approximate "open" with
        // `isVisible`, since the user's perception is whether they can see it.
        bus.subscribe(
            com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
            object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: com.intellij.openapi.wm.ToolWindowManager) {
                    val tw = toolWindowManager.getToolWindow("SAFE-Red") ?: return
                    val nowVisible = tw.isVisible
                    if (nowVisible == safePanelVisible) return
                    val ts = System.currentTimeMillis()
                    if (nowVisible) {
                        recorder.record(
                            event = "panel.opened",
                            data = mapOf("panel" to "safe_tool_window"),
                        )
                        safePanelVisibleSinceMs = ts
                    } else {
                        recorder.record(
                            event = "panel.closed",
                            data = mapOf("panel" to "safe_tool_window"),
                        )
                        if (safePanelVisibleSinceMs > 0L) {
                            recorder.record(
                                event = "panel.dwell",
                                data = mapOf(
                                    "panel" to "safe_tool_window",
                                    "ms" to (ts - safePanelVisibleSinceMs).coerceAtLeast(0L),
                                ),
                            )
                            safePanelVisibleSinceMs = 0L
                        }
                    }
                    safePanelVisible = nowVisible
                }
            },
        )

        // Catch up on already-open editors at startup so the listener works
        // even if the participant doesn't reopen any file before triggering an
        // event we care about.
        FileEditorManager.getInstance(project).openFiles.forEach { ensureListenersAttached(it) }
    }

    // ── FileEditorManager hooks ─────────────────────────────────────────

    private fun onFileOpened(file: VirtualFile) {
        if (!StudyModeSettings.getInstance().enabled) return
        val now = System.currentTimeMillis()
        openedAt[file.path] = now
        recorder.record(
            event = "editor.file_opened",
            data = mapOf(
                "file" to file.path,
                "name" to file.name,
            ),
        )
    }

    private fun onFileClosed(file: VirtualFile) {
        if (!StudyModeSettings.getInstance().enabled) return
        flushModFor(file.path)
        val openedTs = openedAt.remove(file.path) ?: return
        recorder.record(
            event = "editor.file_closed",
            data = mapOf(
                "file" to file.path,
                "dwell_ms" to (System.currentTimeMillis() - openedTs).coerceAtLeast(0L),
            ),
        )
        // If the caret was last seen inside a finding in this file, emit a
        // synthetic `editor.left_finding` so the bracket closes cleanly.
        caretInside.remove(file.path)?.let { findingId ->
            recorder.record(
                event = "editor.left_finding",
                findingId = findingId,
                data = mapOf("file" to file.path, "reason" to "file_closed"),
            )
        }
    }

    private fun onActiveFileChanged(oldFile: VirtualFile?, newFile: VirtualFile?) {
        if (!StudyModeSettings.getInstance().enabled) return
        val now = System.currentTimeMillis()
        if (oldFile != null && oldFile == activeFile) {
            recorder.record(
                event = "editor.active_file_changed",
                data = mapOf(
                    "from" to oldFile.path,
                    "to" to (newFile?.path ?: "(none)"),
                    "ms_since_last_switch" to (now - activeFileSinceMs).coerceAtLeast(0L),
                ),
            )
        }
        activeFile = newFile
        activeFileSinceMs = now
    }

    // ── Per-editor listener attachment ──────────────────────────────────

    private fun ensureListenersAttached(file: VirtualFile) {
        val document = FileEditorManager.getInstance(project)
            .selectedTextEditor
            ?.takeIf { it.virtualFile == file }
            ?.document
            ?: return
        if (!attachedDocs.add(document)) return

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!StudyModeSettings.getInstance().enabled) return
                val path = file.path
                val acc = pendingMods.getOrPut(path) { ModAccumulator() }
                acc.charsAdded += event.newLength
                acc.charsRemoved += event.oldLength
                acc.lastEventAt = System.currentTimeMillis()
                modAlarm.cancelAllRequests()
                modAlarm.addRequest({ flushPendingMods() }, 500)
            }
        }, this)

        FileEditorManager.getInstance(project).selectedTextEditor
            ?.takeIf { it.virtualFile == file }
            ?.caretModel
            ?.addCaretListener(object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    if (!StudyModeSettings.getInstance().enabled) return
                    handleCaretMoved(file, event.newPosition.line + 1)
                }
            }, this)
    }

    // ── Caret-in-finding tracking ───────────────────────────────────────

    private fun handleCaretMoved(file: VirtualFile, line1Based: Int) {
        val findings = storage.getForFile(relativePath(file))
            .map { it.vulnerability }
            .filter { it.startLine != null }
        val now = System.currentTimeMillis()

        // Find a finding whose line range contains the caret.
        val match = findings.firstOrNull { fits(it, line1Based) }
        val previous = caretInside[file.path]
        val matchId = match?.let { findingIdOf(it) }

        if (matchId == previous) return

        if (previous != null) {
            recorder.record(
                event = "editor.left_finding",
                findingId = previous,
                data = mapOf("file" to file.path, "reason" to "caret_moved"),
            )
            caretInside.remove(file.path)
        }
        if (matchId != null) {
            recorder.record(
                event = "editor.reached_finding",
                findingId = matchId,
                data = mapOf(
                    "file" to file.path,
                    "line" to line1Based,
                ),
            )
            caretInside[file.path] = matchId
        }
        @Suppress("UNUSED_VARIABLE") val _ts = now // kept for future dwell calc
    }

    private fun fits(v: VulnerabilityInfo, line: Int): Boolean {
        val start = v.startLine ?: return false
        val end = v.endLine ?: start
        return line in start..end
    }

    private fun findingIdOf(v: VulnerabilityInfo): FindingId =
        "${v.inspectionId}::${v.filePath}::${v.startLine ?: 0}::${v.endLine ?: 0}"

    private fun relativePath(file: VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        val abs = file.path
        return if (abs.startsWith("$basePath/")) abs.removePrefix("$basePath/") else abs
    }

    // ── Modification debouncing ─────────────────────────────────────────

    private fun flushPendingMods() {
        val snapshot = pendingMods.toMap()
        pendingMods.clear()
        for ((path, acc) in snapshot) {
            recorder.record(
                event = "editor.modified",
                data = mapOf(
                    "file" to path,
                    "chars_added" to acc.charsAdded,
                    "chars_removed" to acc.charsRemoved,
                ),
            )
        }
    }

    private fun flushModFor(path: String) {
        val acc = pendingMods.remove(path) ?: return
        recorder.record(
            event = "editor.modified",
            data = mapOf(
                "file" to path,
                "chars_added" to acc.charsAdded,
                "chars_removed" to acc.charsRemoved,
                "reason" to "file_closed",
            ),
        )
    }

    override fun dispose() {
        flushPendingMods()
        attachedDocs.clear()
        openedAt.clear()
        pendingMods.clear()
        caretInside.clear()
    }

    private class ModAccumulator {
        var charsAdded: Int = 0
        var charsRemoved: Int = 0
        var lastEventAt: Long = 0L
    }

    /**
     * Project-startup activity that lazy-instantiates the listener once per
     * project. Registered via `plugin.xml` `postStartupActivity`.
     */
    class StartupActivity : ProjectActivity {
        override suspend fun execute(project: Project) {
            // Construct on the EDT — the listener attaches Swing-thread listeners.
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                project.getService(EditorListenerHolder::class.java).attach(project)
            }
        }
    }

    /**
     * Project service that owns the [StudyEditorListener] instance, ensuring it
     * lives exactly as long as the project. Constructed by IntelliJ's service
     * machinery, so its lifecycle integrates cleanly with the platform's
     * Disposable graph.
     */
    @com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
    class EditorListenerHolder : Disposable {
        @Volatile private var listener: StudyEditorListener? = null

        fun attach(project: Project) {
            if (listener != null) return
            listener = StudyEditorListener(project)
        }

        override fun dispose() {
            // Listener registers itself with the project via Disposer, so it
            // tears down automatically when the project closes.
            listener = null
        }
    }
}

private typealias FindingId = String
