package de.fraunhofer.iem.safe.blue.study

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
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.Alarm
import de.fraunhofer.iem.safe.blue.ExplanationToolWindow
import de.fraunhofer.iem.safe.blue.sast.Issue

/**
 * Records study-mode editor telemetry for the v1 plugin: file opens / closes,
 * active-file shuffle, caret entering / leaving a finding's line range, and
 * net character add / remove counts per file (debounced ~500 ms). Also tracks
 * SAFE tool-window visibility (`panel.opened` / `panel.closed` / `panel.dwell`).
 *
 * Differs from the new plugin's listener only in how findings are resolved:
 * v1 has no [ExplanationStorageService][de.fraunhofer.iem.safe.blue.ui.panel] —
 * findings live on `ExplanationToolWindow.resultsTree`, which we walk on
 * demand to figure out whether a caret moved into a known finding range.
 */
class StudyEditorListener(private val project: Project) : Disposable {

    private val recorder: TelemetryRecorder = TelemetryRecorder.getInstance(project)

    private val openedAt: MutableMap<String, Long> = mutableMapOf()
    @Volatile private var activeFile: VirtualFile? = null
    @Volatile private var activeFileSinceMs: Long = 0L
    private val pendingMods: MutableMap<String, ModAccumulator> = mutableMapOf()
    private val caretInside: MutableMap<String, FindingId> = mutableMapOf()
    private val modAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile private var safePanelVisible: Boolean = false
    @Volatile private var safePanelVisibleSinceMs: Long = 0L

    private val attachedDocs: MutableSet<Document> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    init {
        Disposer.register(project, this)

        val bus = project.messageBus.connect(this)
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = onFileOpened(file)
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = onFileClosed(file)
            override fun selectionChanged(event: FileEditorManagerEvent) {
                onActiveFileChanged(event.oldFile, event.newFile)
                event.newFile?.let { ensureListenersAttached(it) }
            }
        })

        bus.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: com.intellij.openapi.wm.ToolWindowManager) {
                val tw = toolWindowManager.getToolWindow("SAFE-Blue") ?: return
                val nowVisible = tw.isVisible
                if (nowVisible == safePanelVisible) return
                val ts = System.currentTimeMillis()
                if (nowVisible) {
                    recorder.record("panel.opened", data = mapOf("panel" to "safe_tool_window"))
                    safePanelVisibleSinceMs = ts
                } else {
                    recorder.record("panel.closed", data = mapOf("panel" to "safe_tool_window"))
                    if (safePanelVisibleSinceMs > 0L) {
                        recorder.record(
                            "panel.dwell",
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
        })

        FileEditorManager.getInstance(project).openFiles.forEach { ensureListenersAttached(it) }
    }

    private fun onFileOpened(file: VirtualFile) {
        if (!StudyModeSettings.getInstance().enabled) return
        val now = System.currentTimeMillis()
        openedAt[file.path] = now
        recorder.record(
            "editor.file_opened",
            data = mapOf("file" to file.path, "name" to file.name),
        )
    }

    private fun onFileClosed(file: VirtualFile) {
        if (!StudyModeSettings.getInstance().enabled) return
        flushModFor(file.path)
        val openedTs = openedAt.remove(file.path) ?: return
        recorder.record(
            "editor.file_closed",
            data = mapOf(
                "file" to file.path,
                "dwell_ms" to (System.currentTimeMillis() - openedTs).coerceAtLeast(0L),
            ),
        )
        caretInside.remove(file.path)?.let { findingId ->
            recorder.record(
                "editor.left_finding",
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
                "editor.active_file_changed",
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

    private fun ensureListenersAttached(file: VirtualFile) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        if (editor.virtualFile != file) return
        val document = editor.document
        if (!attachedDocs.add(document)) return

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!StudyModeSettings.getInstance().enabled) return
                val acc = pendingMods.getOrPut(file.path) { ModAccumulator() }
                acc.charsAdded += event.newLength
                acc.charsRemoved += event.oldLength
                acc.lastEventAt = System.currentTimeMillis()
                modAlarm.cancelAllRequests()
                modAlarm.addRequest({ flushPendingMods() }, 500)
            }
        }, this)

        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (!StudyModeSettings.getInstance().enabled) return
                handleCaretMoved(file, event.newPosition.line + 1)
            }
        }, this)
    }

    private fun handleCaretMoved(file: VirtualFile, line1Based: Int) {
        val findings = findingsForFile(relativePath(file))
        val match = findings.firstOrNull { fits(it, line1Based) }
        val previous = caretInside[file.path]
        val matchId = match?.let { findingIdOf(it) }

        if (matchId == previous) return

        if (previous != null) {
            recorder.record(
                "editor.left_finding",
                findingId = previous,
                data = mapOf("file" to file.path, "reason" to "caret_moved"),
            )
            caretInside.remove(file.path)
        }
        if (matchId != null) {
            recorder.record(
                "editor.reached_finding",
                findingId = matchId,
                data = mapOf("file" to file.path, "line" to line1Based),
            )
            caretInside[file.path] = matchId
        }
    }

    /**
     * v1 keeps findings on the static `ExplanationToolWindow.resultsTree`. Walk
     * its issues each time we need them — small enough for a single project's
     * SARIF, no need to cache.
     */
    private fun findingsForFile(relativePath: String): List<Issue> {
        val tree = ExplanationToolWindow.resultsTree ?: return emptyList()
        val all = tree.allIssuesOrEmpty()
        return all.filter {
            val path = it.location.fileName
            path == relativePath || relativePath.endsWith(path) || path.endsWith(relativePath)
        }
    }

    private fun fits(v: Issue, line: Int): Boolean {
        val start = v.location.startLine
        val end = v.location.endLine.takeIf { it > 0 } ?: start
        return line in start..end
    }

    private fun findingIdOf(v: Issue): FindingId =
        "${v.type}::${v.location.fileName}::${v.location.startLine}::${v.location.endLine}"

    private fun relativePath(file: VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        val abs = file.path
        return if (abs.startsWith("$basePath/")) abs.removePrefix("$basePath/") else abs
    }

    private fun flushPendingMods() {
        val snapshot = pendingMods.toMap()
        pendingMods.clear()
        for ((path, acc) in snapshot) {
            recorder.record(
                "editor.modified",
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
            "editor.modified",
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

    /** Project-startup activity that lazy-instantiates the listener once per project. */
    class StartupActivity : ProjectActivity {
        override suspend fun execute(project: Project) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                project.getService(EditorListenerHolder::class.java).attach(project)
            }
        }
    }

    @com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
    class EditorListenerHolder : Disposable {
        @Volatile private var listener: StudyEditorListener? = null

        fun attach(project: Project) {
            if (listener != null) return
            listener = StudyEditorListener(project)
        }

        override fun dispose() { listener = null }
    }
}

private typealias FindingId = String
