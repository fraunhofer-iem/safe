package de.fraunhofer.iem.fixmysast.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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
        val jsonFilter = FileNameExtensionFilter("SARIF Files", "sarif")
        fileChooser.fileFilter = jsonFilter

        val returnValue = fileChooser.showOpenDialog(null)

        if (returnValue == JFileChooser.APPROVE_OPTION) {
            val selectedFile = fileChooser.selectedFile

            //Save file location
            PropertiesComponent.getInstance(e.project!!)
                .setValue("de.fraunhofer.iem.fixmysast.actions.SastFile", selectedFile.absolutePath)
        }
    }
}