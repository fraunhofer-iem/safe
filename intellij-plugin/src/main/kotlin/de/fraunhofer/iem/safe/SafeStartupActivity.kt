package de.fraunhofer.iem.safe

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import de.fraunhofer.iem.safe.ui.ExplanationStorageService
import de.fraunhofer.iem.safe.ui.VulnerabilityHighlightService
import de.fraunhofer.iem.safe.util.FindingsSnapshotService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On project open, restores the last imported set of findings (Qodana or SARIF) from
 * [FindingsSnapshotService] into [ExplanationStorageService] so the SAFE tool window
 * has data ready when the user opens it. Highlights are only applied to files that
 * are already open — closed files get them when the user opens them, via
 * `VulnerabilityEditorListener`.
 */
class SafeStartupActivity : ProjectActivity {

    private val logger = Logger.getInstance(SafeStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        val findings = FindingsSnapshotService.getInstance(project).loadFindings() ?: return
        if (findings.isEmpty()) return

        val storage = ExplanationStorageService.getInstance(project)
        for (finding in findings) {
            if (finding.filePath == null || finding.startLine == null) continue
            storage.store(ExplanationStorageService.ExplanationEntry(finding, ""))
        }
        logger.info("SAFE: restored ${findings.size} findings from snapshot.")

        // Highlight already-open files (avoid opening additional editors at startup).
        withContext(Dispatchers.EDT) {
            applyHighlightsToOpenFiles(project, findings)
        }
    }

    private fun applyHighlightsToOpenFiles(project: Project, findings: List<de.fraunhofer.iem.safe.sast.VulnerabilityInfo>) {
        val openFiles = FileEditorManager.getInstance(project).openFiles.toSet()
        if (openFiles.isEmpty()) return
        val basePath = project.basePath ?: return
        val highlightService = VulnerabilityHighlightService.getInstance(project)
        for (finding in findings) {
            if (finding.filePath == null || finding.startLine == null) continue
            val virtualFile = LocalFileSystem.getInstance()
                .findFileByPath("$basePath/${finding.filePath}") ?: continue
            if (virtualFile in openFiles) {
                highlightService.applyHighlight(project, finding, explanation = "")
            }
        }
    }
}
