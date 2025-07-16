package de.fraunhofer.iem.fixmysast.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import de.fraunhofer.iem.fixmysast.sast.Issue
import de.fraunhofer.iem.fixmysast.sast.Results
import javax.swing.JTree
import javax.swing.UIManager
import javax.swing.tree.DefaultMutableTreeNode

class ResultsTreeRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        print("Renderer called for value: $value")
        setIcon(AllIcons.General.BalloonInformation)

        if (value is DefaultMutableTreeNode) {
            val node = value.getUserObject()

            if (node is Results) {
                clear()
                val results: Results = node
                icon = AllIcons.General.BalloonInformation

                println("making rende")


           append(results.issues.count().toString() + " Problems")


            }else if (node is Issue){
                clear()
                val issue: Issue = node
                append(issue.type, SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)

               // icon= com.intellij.icons.AllIcons.General.Information
                icon = if(node.explanation!!.isBlank())
                    UIManager.getIcon("OptionPane.errorIcon")
                else
                    AllIcons.General.InspectionsOK
                val testIcon = AllIcons.General.BalloonInformation
                print("Test icon is null? ${testIcon == null}")

            }
        }
    }

    override fun setToolTipText(text: String?) {

    }
}
