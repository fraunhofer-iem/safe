package de.fraunhofer.iem.safe.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.fraunhofer.iem.safe.sast.Cwe
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath


data class ExplanationTreeEntry(
    val inspectionId: String,
    val cwe: Cwe?,
    val filePath: String?,
    val htmlExplanation: String,
    val rawResponse: String
)

data class CweNodeEntry(val cwe: Cwe, var count: Int = 0)
data class DirectoryNodeEntry(val path: String)

class ExplainPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val rootNode = DefaultMutableTreeNode("Explanations")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = ExplanationTreeCellRenderer()
        emptyText.text = "Select a vulnerability from Qodana for explanations"
    }

    private fun updateEmptyState() {
        val isEmpty = explanations.isEmpty()
        tree.isRootVisible = !isEmpty
    }
    private val explanations = mutableMapOf<String, ExplanationTreeEntry>()

    private val contentArea = JEditorPane().apply {
        isEditable = false
        val kit = HTMLEditorKit()
        //kit.styleSheet = createStyleSheet()
        editorKit = kit
        background = UIUtil.getPanelBackground()
    }

    private val detailPanel = JPanel(BorderLayout()).apply {
        add(JBScrollPane(contentArea), BorderLayout.CENTER)
    }



    private val treePanel = JPanel(BorderLayout()).apply {
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    private val splitter = JBSplitter(false, 0.3f).apply {
        firstComponent = treePanel
        secondComponent = detailPanel
    }

    companion object {
        fun splitPath(filePath: String): Pair<String, String> {
            if (filePath.isBlank()) return Pair("", "")
            val lastSep = filePath.lastIndexOfAny(charArrayOf('/', '\\'))
            return if (lastSep >= 0) {
                Pair(filePath.substring(0, lastSep), filePath.substring(lastSep + 1))
            } else {
                Pair("", filePath)
            }
        }

        /**
         * Two CWE nodes are considered the same if their ids match (when both are
         * non-null), otherwise fall back to structural equality.

         */
        fun cwesMatch(a: Cwe, b: Cwe): Boolean =
            if (a.id != null && b.id != null) a.id == b.id else a == b
    }

    init {
        add(splitter, BorderLayout.CENTER)

        tree.addTreeSelectionListener {
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                ?: return@addTreeSelectionListener
            val userObject = selectedNode.userObject
            if (userObject is ExplanationTreeEntry) {
                setHtmlContent(formatEntryAsHtml(userObject))
            }
        }

        loadCachedExplanations()
    }

    // ── Tree Structure Helpers ────────────────────────────────────────────────

    private fun insertEntryIntoTree(entry: ExplanationTreeEntry) {
        val cwe = entry.cwe ?: Cwe(id = entry.inspectionId)
        val (dirPath, _) = splitPath(entry.filePath ?: "")

        val cweNode = findOrCreateCweNode(cwe)
        (cweNode.userObject as CweNodeEntry).count++

        if (dirPath.isBlank()) {
            cweNode.add(DefaultMutableTreeNode(entry))
        } else {
            val dirNode = findOrCreateDirNode(cweNode, dirPath)
            dirNode.add(DefaultMutableTreeNode(entry))
        }
    }

    private fun findOrCreateCweNode(cwe: Cwe): DefaultMutableTreeNode {
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val nodeEntry = child.userObject as? CweNodeEntry ?: continue
            if (cwesMatch(nodeEntry.cwe, cwe)) return child
        }
        return DefaultMutableTreeNode(CweNodeEntry(cwe, 0)).also { rootNode.add(it) }
    }

    private fun findOrCreateDirNode(
        cweNode: DefaultMutableTreeNode,
        dirPath: String
    ): DefaultMutableTreeNode {
        for (i in 0 until cweNode.childCount) {
            val child = cweNode.getChildAt(i) as DefaultMutableTreeNode
            if ((child.userObject as? DirectoryNodeEntry)?.path == dirPath) return child
        }
        return DefaultMutableTreeNode(DirectoryNodeEntry(dirPath)).also { cweNode.add(it) }
    }

    private fun selectEntryInTree(entry: ExplanationTreeEntry) {
        val targetCwe = entry.cwe ?: Cwe(id = entry.inspectionId)
        val key = "${entry.inspectionId}::${entry.filePath ?: ""}"

        for (i in 0 until rootNode.childCount) {
            val cweNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val nodeEntry = cweNode.userObject as? CweNodeEntry ?: continue
            if (!cwesMatch(nodeEntry.cwe, targetCwe)) continue

            for (j in 0 until cweNode.childCount) {
                val dirNode = cweNode.getChildAt(j) as DefaultMutableTreeNode
                for (k in 0 until dirNode.childCount) {
                    val fileNode = dirNode.getChildAt(k) as DefaultMutableTreeNode
                    val obj = fileNode.userObject as? ExplanationTreeEntry ?: continue
                    if ("${obj.inspectionId}::${obj.filePath ?: ""}" == key) {
                        val path = TreePath(fileNode.path)
                        tree.selectionPath = path
                        tree.scrollPathToVisible(path)
                        return
                    }
                }
            }
        }
    }

    private fun updateEntryInTree(entry: ExplanationTreeEntry, key: String) {
        val targetCwe = entry.cwe ?: Cwe(id = entry.inspectionId)

        for (i in 0 until rootNode.childCount) {
            val cweNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val nodeEntry = cweNode.userObject as? CweNodeEntry ?: continue
            if (!cwesMatch(nodeEntry.cwe, targetCwe)) continue

            for (j in 0 until cweNode.childCount) {
                val dirNode = cweNode.getChildAt(j) as DefaultMutableTreeNode
                for (k in 0 until dirNode.childCount) {
                    val fileNode = dirNode.getChildAt(k) as DefaultMutableTreeNode
                    val obj = fileNode.userObject as? ExplanationTreeEntry ?: continue
                    if ("${obj.inspectionId}::${obj.filePath ?: ""}" == key) {
                        fileNode.userObject = entry
                        treeModel.nodeChanged(fileNode)
                        return
                    }
                }
            }
        }
    }


    // ── Public API ────────────────────────────────────────────────────────────

    private fun loadCachedExplanations() {
        val cache = ExplanationCacheService.getInstance(project)
        for (cached in cache.getAll()) {

            // Cache stores cwe as a plain id string — wrap it into a Cwe object
            val cwe = cached.cwe?.let { Cwe(id = it) }
            val entry = ExplanationTreeEntry(cached.inspectionId, cwe, cached.fileName, "", cached.response)
            val key = "${cached.inspectionId}::${cached.fileName ?: ""}"
            explanations[key] = entry
            insertEntryIntoTree(entry)
        }
        if (rootNode.childCount > 0) {
            treeModel.reload(rootNode)
            tree.expandRow(0)
        }
        updateEmptyState()
    }

    fun showCachedIfAvailable(inspectionId: String?, fileName: String?): Boolean {
        val id = inspectionId ?: return false
        val key = "$id::${fileName ?: ""}"
        val entry = explanations[key] ?: return false
        setHtmlContent(formatEntryAsHtml(entry))
        selectEntryInTree(entry)
        return true
    }

    /**
     * @param cwe  Full [Cwe] object (id + optional human-readable name).

     */
    fun showExplanation(inspectionId: String?, cwe: Cwe?, fileName: String?, response: String) {
        val id = inspectionId ?: "Unknown vulnerability"
        val entry = ExplanationTreeEntry(id, cwe, fileName, "", response)
        val key = "$id::${fileName ?: ""}"

        // Persist only the id — the name can be re-supplied on next showExplanation call
        ExplanationCacheService.getInstance(project).store(id, cwe?.id, fileName, response)

        if (!explanations.containsKey(key)) {
            explanations[key] = entry
            insertEntryIntoTree(entry)
            treeModel.reload(rootNode)
            tree.expandRow(0)
        } else {
            explanations[key] = entry
            updateEntryInTree(entry, key)
        }
        updateEmptyState()
        selectEntryInTree(entry)
        setHtmlContent(formatEntryAsHtml(entry))
    }

    fun showError(message: String) {
        setHtmlContent(
            """
            <html><body>
               <p><b>SQL Injection</b></p> 
               <div style="border-left:3px solid #d9534f; ">
                
                 <p class='error'><b>SQL Injection</b></p>
                    <p class='error'><b>Error</b></p>
                    <p class='error'>$message</p>
                </div>
            </body></html>
            """.trimIndent()
        )
    }

    // ── Section Parsing ───────────────────────────────────────────────────────

    private fun parseResponseSections(response: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pattern = Regex("""\*\*(WHAT|WHERE|WHY|HOW)\*\*\s*:?\s*""", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(response).toList()
        for (i in matches.indices) {
            val key = matches[i].groupValues[1].lowercase()
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else response.length
            result[key] = response.substring(start, end).trim()
        }
        return result
    }

    private fun String.toHtmlParagraphs(): String =
        split("\n\n").filter { it.isNotBlank() }
            .joinToString("") { "<p style='margin:4px 0;'>${it.replace("\n", "<br/>")}</p>" }

    private fun adjustBrightness(color: Color, delta: Int): Color =
        Color(
            (color.red   + delta).coerceIn(0, 255),
            (color.green + delta).coerceIn(0, 255),
            (color.blue  + delta).coerceIn(0, 255)
        )

    // ── Rendering Helpers ─────────────────────────────────────────────────────

    private fun setHtmlContent(html: String) {
        val kit = contentArea.editorKit as HTMLEditorKit
        val doc = kit.createDefaultDocument()
        contentArea.document = doc
        contentArea.text = html
        contentArea.caretPosition = 0
        contentArea.revalidate()
        contentArea.repaint()
    }

    private fun createStyleSheet(): StyleSheet {
        val fg = colorToHex(UIUtil.getLabelForeground())
        val muted = colorToHex(UIUtil.getContextHelpForeground())
        val accent = colorToHex(JBUI.CurrentTheme.Link.Foreground.ENABLED)

        return StyleSheet().apply {
            addRule("body { font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; font-size: 11pt; color: $fg;  line-height: 1.7; }")
            addRule("h3 { color: $accent; font-size: 13pt; margin-top: 18px; margin-bottom: 4px; padding-bottom: 4px; border-bottom: 1px solid $muted; }")
            addRule(".error { color: #e88; }")
            addRule(".muted { color: $muted; font-style: italic; }")
        }
    }

    private fun formatEntryAsHtml(entry: ExplanationTreeEntry): String {
        val sections = parseResponseSections(entry.rawResponse)
        val fgHex = colorToHex(UIUtil.getLabelForeground())
        val panelHex = colorToHex(UIUtil.getPanelBackground())
        val mutedHex = colorToHex(UIUtil.getContextHelpForeground())
        val linkHex = colorToHex(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        val borderHex = colorToHex(adjustBrightness(UIUtil.getPanelBackground(), if (UIUtil.isUnderDarcula()) 30 else -25))

        val cweId = entry.cwe?.id ?: entry.inspectionId
        val cweName = entry.cwe?.name ?: ""

        val whatText = sections["what"] ?: ""
        val whyText = sections["why"] ?: ""
        val whereText = sections["where"] ?: ""
        val howText = sections["how"] ?: ""

        return buildString {
            appendLine("""
            <html>
            <head><style>
                body {
                    margin: 0; padding: 0;
                    background-color: $panelHex;
                    font-family: '${UIUtil.getLabelFont().family}', sans-serif;
                    font-size: ${UIUtil.getLabelFont().size}pt;
                    color: $fgHex;
                }
                .header {
                    padding: 10px 14px;
                    border-bottom: 1px solid $borderHex;
                }
                .header .id {
                    font-size: ${UIUtil.getLabelFont().size + 2}pt;
                    font-weight: bold;
                    color: $linkHex;
                }
                .header .name {
                    color: $mutedHex;
                    font-size: ${UIUtil.getLabelFont().size}pt;
                    margin-left: 6px;
                }
                .header .filepath {
                    color: $mutedHex;
                    font-size: ${UIUtil.getLabelFont().size - 1}pt;
                    font-family: monospace;
                    margin-top: 4px;
                }
                .content {
                    padding: 6px 14px 14px 14px;
                }
                .section {
                    margin-top: 10px;
                }
                .section-title {
                    font-weight: bold;
                    font-size: ${UIUtil.getLabelFont().size}pt;
                    color: $fgHex;
                    margin: 0 0 4px 0;
                    padding: 0;
                }
                .section-body {
                    color: $fgHex;
                    line-height: 1.5;
                    margin: 0;
                }
                .section-body p {
                    margin: 2px 0;
                }
                .separator {
                    border: none;
                    border-top: 1px solid $borderHex;
                    margin: 10px 0 0 0;
                }
            </style></head>
            <body>
        """.trimIndent())

            // Header
            appendLine("""<div class="header">""")
            appendLine("""<span class="id">$cweId</span>""")
            if (cweName.isNotBlank()) {
                appendLine("""<span class="name">$cweName</span>""")
            }
            if (entry.filePath != null) {
                appendLine("""<div class="filepath">${entry.filePath}</div>""")
            }
            appendLine("""</div>""")

            // Content
            appendLine("""<div class="content">""")

            var first = true
            fun appendSection(title: String, body: String) {
                if (body.isBlank()) return

                first = false
                appendLine("""
                <div class="section">
                    <p class="section-title">$title</p>
                    <div class="section-body">${body.toHtmlParagraphs()}</div>
                </div>
            """.trimIndent())
            }

            appendSection("What", whatText)
            appendSection("Why", whyText)
            appendSection("Where", whereText)
            appendSection("How to fix", howText)

            appendLine("""</div></body></html>""")
        }
    }

    private fun colorToHex(color: Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)


    fun expandAllNodes() {
        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }
    }

    fun collapseAllNodes() {
        for (i in tree.rowCount - 1 downTo 1) {
            tree.collapseRow(i)
        }
    }

    // ── Cell Renderer ─────────────────────────────────────────────────────────

    private class ExplanationTreeCellRenderer : DefaultTreeCellRenderer() {

        init {
            borderSelectionColor = null
        }

        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, false)
            val node = value as? DefaultMutableTreeNode ?: return component

            when (val obj = node.userObject) {
                is String -> {
                    val total = (0 until node.childCount).sumOf { i ->
                        ((node.getChildAt(i) as? DefaultMutableTreeNode)
                            ?.userObject as? CweNodeEntry)?.count ?: 0
                    }
                    text = "<html><b>$obj</b> <font color='gray'>($total)</font></html>"
                    font = font.deriveFont(Font.BOLD)
                    icon = null
                }
                is CweNodeEntry -> {
                    // Prefer the human-readable name; show the id as a gray subtitle when available
                    val id = obj.cwe.id ?: ""
                    val name = obj.cwe.name ?: ""

                    text = "<html><b>$id</b> $name <font color='gray'>(${obj.count})</font></html>"
                    font = font.deriveFont(Font.PLAIN)
                    icon = null
                }
                is DirectoryNodeEntry -> {
                    text = obj.path.ifBlank { "(root)" }
                    font = font.deriveFont(Font.PLAIN)
                    icon = AllIcons.Nodes.Folder
                }
                is ExplanationTreeEntry -> {
                    val (_, fileName) = splitPath(obj.filePath ?: "")
                    text = fileName.ifBlank { obj.filePath ?: obj.inspectionId }
                    font = font.deriveFont(Font.PLAIN)
                    icon = AllIcons.Nodes.Class
                }
            }

            return component
        }
    }
}