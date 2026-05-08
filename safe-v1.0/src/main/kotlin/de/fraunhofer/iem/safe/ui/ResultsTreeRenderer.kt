package de.fraunhofer.iem.safe.ui

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import de.fraunhofer.iem.safe.sast.Issue
import de.fraunhofer.iem.safe.sast.IssueLocation
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Tree renderer for the SAFE tool window's results panel.
 *
 * The previous version only called `append(...)` for `Issue` nodes — every other
 * node type (the root holding the file path, the "Tool: N Problems" wrapper,
 * `IssueLocation` rows, plain message strings) fell through silently and rendered
 * as a blank cell. Initial paints sometimes hid this because Swing cached cell
 * snapshots, but a theme change invalidates that cache and forces a re-render,
 * surfacing the empty cells. The icon was leaking across cells for the same
 * reason — it wasn't reset for non-Issue rows.
 *
 * Every branch now sets text via `append` and resets `icon`, so cells render
 * consistently across themes.
 */
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
        // Always start with a clean slate — ColoredTreeCellRenderer doesn't
        // guarantee the icon is null on entry, and a stale icon from the previous
        // cell is what produced the rogue "+" sign in the light theme.
        icon = null

        if (value !is DefaultMutableTreeNode) return
        when (val obj = value.userObject) {
            is Issue -> {
                append(obj.message + " Problems")
                append(obj.type, SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
            }
            is IssueLocation -> {
                val location = buildString {
                    append(obj.fileName ?: "")
                    if (obj.startLine > 0) append(":").append(obj.startLine)
                }
                append(location.ifBlank { obj.toString() })
            }
            null -> {
                // The model occasionally stores nodes without a user object
                // (e.g., placeholder roots). Render nothing rather than crashing.
            }
            else -> {
                // Root nodes, "Tool: N Problems" wrappers, and message-string
                // children all hold plain Strings. Render their toString() so the
                // text is visible in every theme.
                append(obj.toString())
            }
        }
    }
}
