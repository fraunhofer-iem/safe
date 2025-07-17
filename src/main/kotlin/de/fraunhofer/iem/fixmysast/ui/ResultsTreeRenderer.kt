package de.fraunhofer.iem.fixmysast.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import de.fraunhofer.iem.fixmysast.sast.Issue
import javax.swing.JTree
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
        if (value is DefaultMutableTreeNode) {
            val node = value.getUserObject()

            if (node is Issue) {
                val issue: Issue = node

                append(issue.message + " Problems")
                append(issue.type, SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
                icon = AllIcons.General.Add

            }
        }
    }
}