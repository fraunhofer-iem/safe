package de.fraunhofer.iem.safe.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import de.fraunhofer.iem.safe.sast.QodanaNodeExtractor
import de.fraunhofer.iem.safe.sast.SarifParser
import de.fraunhofer.iem.safe.sast.VulnerabilityInfo
import de.fraunhofer.iem.safe.ui.ExplainPanel
import de.fraunhofer.iem.safe.ui.ExplanationStorageService
import de.fraunhofer.iem.safe.ui.VulnerabilityHighlightService
import de.fraunhofer.iem.safe.util.FindingsSnapshotService
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Imports all problems for the project from either:
 *   1. The Qodana plugin's currently-displayed results (read directly from the Qodana tool
 *      window's tree via [QodanaNodeExtractor.extractAll]), or
 *   2. A SARIF file the user picks via a file chooser.
 *
 * Imported findings populate the SAFE tool window as placeholder entries and apply editor
 * highlights. Explanations are not generated in bulk — the user runs "Explain Vulnerability"
 * on individual findings.
 */
class ImportProblemsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val choice = Messages.showDialog(
            project,
            "Choose a source for imported findings.",
            "Import All Problems",
            arrayOf("Qodana Plugin", "SARIF File...", "Cancel"),
            0,
            Messages.getQuestionIcon(),
        )
        when (choice) {
            0 -> importFromQodana(project)
            1 -> importFromSarif(project)
            else -> return
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun importFromQodana(project: Project) {
        val findings = QodanaNodeExtractor.extractAll(project)
        if (findings.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "No findings were found in the Qodana tool window. " +
                    "Open Qodana and run an inspection first, then try again.",
                "Import All Problems",
            )
            return
        }
        FindingsSnapshotService.getInstance(project)
            .save(FindingsSnapshotService.Source.QODANA, findings)
        populatePanel(project, findings, source = "Qodana plugin", interactive = true)
    }

    private fun importFromSarif(project: Project) {
        val sarifPath = chooseSarifFile(project) ?: return
        importFromSarifPath(project, sarifPath, interactive = true)
    }

    private fun chooseSarifFile(project: Project): Path? {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle("Select SARIF File")
            .withDescription("Choose a SARIF file (.sarif or .json) to import findings from")
        val props = PropertiesComponent.getInstance(project)
        val lfs = LocalFileSystem.getInstance()
        val toSelect = props.getValue(LAST_IMPORT_DIR_KEY)?.let { lfs.findFileByPath(it) }
            ?: project.basePath?.let { lfs.findFileByPath(it) }
        val virtualFile = FileChooser.chooseFile(descriptor, project, toSelect) ?: return null
        virtualFile.parent?.path?.let { props.setValue(LAST_IMPORT_DIR_KEY, it) }
        return Paths.get(virtualFile.path)
    }

    companion object {
        private val sharedLogger = Logger.getInstance(ImportProblemsAction::class.java)

        private const val LAST_IMPORT_DIR_KEY = "de.fraunhofer.iem.safe.red.lastImportDir"

        /**
         * Parses a SARIF file and routes findings into the SAFE tool window and editor highlights.
         *
         * @param interactive when true, opens/activates the tool window, applies highlights to
         *        every finding's file (opening files as needed), and shows a completion dialog.
         *        When false (e.g., on startup auto-load), silently stores findings and only
         *        highlights files that are already open.
         */
        fun importFromSarifPath(project: Project, sarifPath: Path, interactive: Boolean) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Importing findings from ${sarifPath.fileName}", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val findings: List<VulnerabilityInfo> = try {
                        SarifParser.parse(sarifPath)
                    } catch (ex: Exception) {
                        sharedLogger.warn("Failed to parse SARIF file: $sarifPath", ex)
                        if (interactive) ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, "Failed to parse SARIF file: ${ex.message}", "Import All Problems")
                        }
                        return
                    }

                    if (findings.isEmpty()) {
                        if (interactive) ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(project, "No findings were found in $sarifPath.", "Import All Problems")
                        }
                        return
                    }

                    FindingsSnapshotService.getInstance(project).save(
                        FindingsSnapshotService.Source.SARIF,
                        findings,
                        sarifPath.toAbsolutePath().toString(),
                    )

                    de.fraunhofer.iem.safe.study.TelemetryRecorder.getInstance(project).record(
                        event = "findings.imported",
                        data = mapOf(
                            "source" to "sarif",
                            "file" to sarifPath.fileName.toString(),
                            "count" to findings.size,
                            "sha256" to sha256Of(sarifPath),
                        ),
                    )

                    ApplicationManager.getApplication().invokeLater {
                        populatePanel(project, findings, sarifPath.fileName.toString(), interactive)
                    }
                }
            })
        }

        private fun populatePanel(project: Project, findings: List<VulnerabilityInfo>, source: String, interactive: Boolean) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("SAFE-Red") ?: return
            val highlightService = VulnerabilityHighlightService.getInstance(project)
            val storage = ExplanationStorageService.getInstance(project)
            for (finding in findings) {
                if (finding.filePath == null || finding.startLine == null) continue
                storage.store(ExplanationStorageService.ExplanationEntry(finding, ""))
            }

            if (interactive) {
                toolWindow.show {
                    val panel = toolWindow.contentManager.getContent(0)?.component as? ExplainPanel ?: return@show
                    val inserted = panel.addFindings(findings)
                    for (finding in findings) {
                        if (finding.filePath == null || finding.startLine == null) continue
                        highlightService.applyHighlight(project, finding, explanation = "")
                    }
                    Messages.showInfoMessage(
                        project,
                        "Imported $inserted of ${findings.size} findings from $source.\n" +
                            "Right-click any finding and choose \"Explain Vulnerability\" to generate an explanation.",
                        "Import All Problems",
                    )
                }
                return
            }

            // Non-interactive (startup) — populate the panel only if it's already been created,
            // and only apply highlights to files that are already open.
            (toolWindow.contentManager.getContent(0)?.component as? ExplainPanel)?.addFindings(findings)
            val openFiles = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.toSet()
            val basePath = project.basePath ?: return
            for (finding in findings) {
                if (finding.filePath == null || finding.startLine == null) continue
                val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByPath("$basePath/${finding.filePath}") ?: continue
                if (virtualFile in openFiles) {
                    highlightService.applyHighlight(project, finding, explanation = "")
                }
            }
        }

        /**
         * SHA-256 of the SARIF file contents — written into `findings.imported` so
         * post-study analysis can confirm both plugins ran on the same input.
         * Returns the empty string if the file can't be read.
         */
        private fun sha256Of(path: Path): String = try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(java.nio.file.Files.readAllBytes(path))
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }
}
