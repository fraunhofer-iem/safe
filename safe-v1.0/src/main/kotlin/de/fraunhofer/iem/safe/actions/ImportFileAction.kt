package de.fraunhofer.iem.safe.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import de.fraunhofer.iem.safe.comm.ParseFileNotifier
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.filechooser.FileSystemView


class ImportFileAction : AnAction(AllIcons.Actions.Install) {

    override fun actionPerformed(e: AnActionEvent) {

        val projectPath = if (e.project!!.basePath == null)
            FileSystemView.getFileSystemView().defaultDirectory
        else File(e.project!!.basePath!!)

        val fileChooser = JFileChooser(projectPath)
        val jsonFilter = FileNameExtensionFilter("SARIF Files", "sarif", "json")
        fileChooser.fileFilter = jsonFilter

        val returnValue = fileChooser.showOpenDialog(null)

        if (returnValue == JFileChooser.APPROVE_OPTION) {
            val selectedFile = fileChooser.selectedFile

            de.fraunhofer.iem.safe.study.TelemetryRecorder.getInstance(e.project!!).record(
                event = "findings.imported",
                data = mapOf(
                    "source" to (if (selectedFile.name.lowercase().endsWith(".sarif")) "sarif" else "json"),
                    "file" to selectedFile.name,
                    "sha256" to sha256Of(selectedFile),
                ),
            )

            val publisher: ParseFileNotifier =
                e.project!!.messageBus.syncPublisher(ParseFileNotifier.PARSE_SARIF_FILE)
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
}