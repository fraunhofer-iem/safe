package de.fraunhofer.iem.safe.blue.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import de.fraunhofer.iem.safe.blue.comm.ParseFileNotifier
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.filechooser.FileSystemView


class ImportFileAction : AnAction(AllIcons.Actions.Install) {

    override fun actionPerformed(e: AnActionEvent) {

        val project = e.project!!
        val props = PropertiesComponent.getInstance(project)
        val initialDir = props.getValue(LAST_IMPORT_DIR_KEY)?.let { File(it).takeIf(File::isDirectory) }
            ?: project.basePath?.let { File(it) }
            ?: FileSystemView.getFileSystemView().defaultDirectory

        val fileChooser = JFileChooser(initialDir)
        val jsonFilter = FileNameExtensionFilter("SARIF Files", "sarif", "json")
        fileChooser.fileFilter = jsonFilter

        val returnValue = fileChooser.showOpenDialog(null)

        if (returnValue == JFileChooser.APPROVE_OPTION) {
            val selectedFile = fileChooser.selectedFile
            selectedFile.parentFile?.absolutePath?.let { props.setValue(LAST_IMPORT_DIR_KEY, it) }

            de.fraunhofer.iem.safe.blue.study.TelemetryRecorder.getInstance(project).record(
                event = "findings.imported",
                data = mapOf(
                    "source" to (if (selectedFile.name.lowercase().endsWith(".sarif")) "sarif" else "json"),
                    "file" to selectedFile.name,
                    "sha256" to sha256Of(selectedFile),
                ),
            )

            val publisher: ParseFileNotifier =
                project.messageBus.syncPublisher(ParseFileNotifier.PARSE_SARIF_FILE)
            publisher.parse(selectedFile.absolutePath)
        }
    }

    private fun sha256Of(file: File): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    companion object {
        private const val LAST_IMPORT_DIR_KEY = "de.fraunhofer.iem.safe.blue.lastImportDir"
    }
}