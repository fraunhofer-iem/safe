package de.fraunhofer.iem.safe.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.ThreeComponentsSplitter
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.fraunhofer.iem.safe.sast.Cwe
import de.fraunhofer.iem.safe.sast.QodanaNodeExtractor
import de.fraunhofer.iem.safe.sast.StepRole
import de.fraunhofer.iem.safe.sast.StepRoleResolver
import de.fraunhofer.iem.safe.sast.TaintStep
import de.fraunhofer.iem.safe.sast.TaintTrace
import de.fraunhofer.iem.safe.sast.VulnerabilityInfo
import de.fraunhofer.iem.safe.ui.icons.CweBadge
import de.fraunhofer.iem.safe.ui.icons.TaintFlowIcons
import de.fraunhofer.iem.safe.util.CweCatalog
import de.fraunhofer.iem.safe.util.FindingsSnapshotService
import de.fraunhofer.iem.safe.util.Glossary
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
    private val tree = object : Tree(treeModel), UiDataProvider {
        override fun getToolTipText(event: MouseEvent): String? {
            val path = getPathForLocation(event.x, event.y) ?: return super.getToolTipText(event)
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
            val cwe = (node.userObject as? CweNodeEntry)?.cwe ?: return null
            val description = cwe.description?.takeIf { it.isNotBlank() } ?: return null
            val id = cwe.id ?: ""
            return "<html><b>$id</b><br/>$description</html>"
        }

        override fun uiDataSnapshot(sink: DataSink) {
            val selected = (lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ExplanationTreeEntry
            sink[SAFE_TREE_ENTRY_KEY] = selected
        }
    }.apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = ExplanationTreeCellRenderer()
        emptyText.text = "Select a vulnerability from Qodana for explanations"
        ToolTipManager.sharedInstance().registerComponent(this)
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
        ToolTipManager.sharedInstance().registerComponent(this)
        addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val href = event.description.orEmpty()
            if (href.endsWith("toggle-sast")) {
                sastMessageExpanded = !sastMessageExpanded
                currentEntry?.let { setHtmlContent(formatEntryAsHtml(it)) }
                return@addHyperlinkListener
            }
            if (href.endsWith("toggle-deepdive")) {
                deepDiveExpanded = !deepDiveExpanded
                currentEntry?.let { setHtmlContent(formatEntryAsHtml(it)) }
                return@addHyperlinkListener
            }
            event.url?.let { BrowserUtil.browse(it) }
        }
    }

    private val detailPanel = JPanel(BorderLayout()).apply {
        add(JBScrollPane(contentArea), BorderLayout.CENTER)
    }



    private val treePanel = JPanel(BorderLayout()).apply {
        add(searchField, BorderLayout.NORTH)
        add(JBScrollPane(tree).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    // Taint flows pane — only attached to the splitter when the selected finding has codeFlows.
    private val flowsRoot = DefaultMutableTreeNode("Flows")
    private val flowsModel = DefaultTreeModel(flowsRoot)
    private val flowsTree = object : Tree(flowsModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val path = getPathForLocation(event.x, event.y) ?: return null
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
            return when (val obj = node.userObject) {
                is TaintStep -> {
                    val location = buildString {
                        obj.filePath?.let { append(it) }
                        obj.startLine?.let { append(":").append(it) }
                    }
                    location.takeIf { it.isNotBlank() }
                }
                is TraceNodeEntry -> obj.title
                else -> null
            }
        }
    }.apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = FlowsTreeCellRenderer()
        emptyText.text = "No taint flows for this finding"
        ToolTipManager.sharedInstance().registerComponent(this)
    }
    /**
     * Per-flow explanation pane below the flows tree. For now we surface the SAST tool's
     * message for the selected trace/step; later this is the place where an LLM-generated
     * explanation for the individual flow would render.
     */
    private val flowsDescriptionArea = object : JEditorPane() {
        override fun getToolTipText(event: MouseEvent): String? = htmlTitleAt(this, event)
    }.apply {
        isEditable = false
        editorKit = HTMLEditorKit()
        background = UIUtil.getPanelBackground()
        ToolTipManager.sharedInstance().registerComponent(this)
        addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val href = event.description.orEmpty()
            if (href.endsWith("toggle-step-original")) {
                stepOriginalExpanded = !stepOriginalExpanded
                handleFlowsSelection(flowsTree.lastSelectedPathComponent as? DefaultMutableTreeNode)
                return@addHyperlinkListener
            }
            event.url?.let { BrowserUtil.browse(it) }
        }
    }

    private val flowsDescriptionScroll = JBScrollPane(flowsDescriptionArea).apply { border = JBUI.Borders.empty() }

    /**
     * The bottom pane (`flowsDescriptionScroll`) is detached by default so the flows tree
     * uses the whole flows column. We attach it on selection and detach again when the
     * user picks a different finding.
     */
    private val flowsSplitter = OnePixelSplitter(true, 0.6f).apply {
        firstComponent = JBScrollPane(flowsTree).apply { border = JBUI.Borders.empty() }
        secondComponent = null
    }

    private val flowsPanel = JPanel(BorderLayout()).apply {
        add(flowsSplitter, BorderLayout.CENTER)
    }

    private val splitter = ThreeComponentsSplitter(false, true).apply {
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
        // Parse the 15 MB CWE catalog off the EDT so the first CWE-row click doesn't stall.
        ApplicationManager.getApplication().executeOnPooledThread { CweCatalog.descriptions }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                applyFilter(searchField.text)
            }
        })
    }

    /**
     * Substring-filters the visible tree against the search field. Matches across the
     * inspection id, file path, CWE id/name, and the SAST message of each entry.
     */
    private fun applyFilter(text: String) {
        val needle = text.trim().lowercase()
        rootNode.removeAllChildren()
        for (entry in explanations.values) {
            if (needle.isEmpty() || entryMatches(entry, needle)) {
                insertEntryIntoTree(entry)
            }
        }
        treeModel.reload(rootNode)
        updateEmptyState()
        if (rootNode.childCount > 0) tree.expandRow(0)
    }

    private fun entryMatches(entry: ExplanationTreeEntry, needle: String): Boolean =
        entry.inspectionId.lowercase().contains(needle) ||
            entry.filePath?.lowercase()?.contains(needle) == true ||
            entry.cwe?.id?.lowercase()?.contains(needle) == true ||
            entry.cwe?.name?.lowercase()?.contains(needle) == true ||
            entry.sastMessage?.lowercase()?.contains(needle) == true

    private fun navigateToEntry(entry: ExplanationTreeEntry) {
        val filePath = entry.filePath ?: return
        val basePath = project.basePath ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath") ?: return
        val line = entry.startLine?.let { (it - 1).coerceAtLeast(0) } ?: 0
        val column = 0
        val descriptor = OpenFileDescriptor(project, virtualFile, line, column)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, false)
    }

    private fun navigateToStep(step: TaintStep) {
        val filePath = step.filePath ?: return
        val basePath = project.basePath ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath") ?: return
        val line = step.startLine?.let { (it - 1).coerceAtLeast(0) } ?: 0
        val column = step.startColumn?.let { (it - 1).coerceAtLeast(0) } ?: 0
        val descriptor = OpenFileDescriptor(project, virtualFile, line, column)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, false)
    }

    private fun lookupTraces(entry: ExplanationTreeEntry): List<TaintTrace> {
        val path = entry.filePath ?: return emptyList()
        val candidates = ExplanationStorageService.getInstance(project).getForFile(path)
            .filter { it.vulnerability.inspectionId == entry.inspectionId }
        // Cache-loaded entries don't carry line numbers (the cache only stores the LLM
        // response), so fall back to the first inspection-id + file match in storage when
        // we don't have line info to match against.
        val match = if (entry.startLine != null) {
            candidates.firstOrNull {
                it.vulnerability.startLine == entry.startLine &&
                    it.vulnerability.endLine == entry.endLine
            } ?: candidates.firstOrNull()
        } else {
            candidates.firstOrNull()
        }
        return match?.vulnerability?.traces.orEmpty()
    }

    /**
     * Populates the flows pane with the given traces. When the list is empty, detaches the
     * pane from the splitter so it doesn't claim screen real estate.
     */
    private fun updateFlowsPane(traces: List<TaintTrace>) {
        flowsRoot.removeAllChildren()
        flowsDescriptionArea.text = ""
        flowsSplitter.secondComponent = null
        currentStepKey = null
        stepOriginalExpanded = false
        cachedStepExplanations = parseStepExplanations(currentEntry?.rawResponse.orEmpty())
        for ((index, trace) in traces.withIndex()) {
            val description = trace.description?.takeIf { it.isNotBlank() } ?: "Trace ${index + 1}"
            val traceNode = DefaultMutableTreeNode(TraceNodeEntry(description, trace))
            for (step in trace.steps) {
                traceNode.add(DefaultMutableTreeNode(step))
            }
            flowsRoot.add(traceNode)
        }
        flowsModel.reload(flowsRoot)
        if (traces.isEmpty()) {
            splitter.lastComponent = null
        } else {
            splitter.lastComponent = flowsPanel
            // Auto-expand the first trace for quick visibility.
            if (flowsRoot.childCount > 0) flowsTree.expandRow(0)
        }
    }

    /**
     * Routes a flows-tree selection into the description pane:
     *  - Trace header → renders the raw trace description.
     *  - Step row    → renders the LLM-generated `**STEP T.S**` explanation when present,
     *                  with a "Show original finding message" toggle that reveals the
     *                  raw SAST message.
     */
    private fun handleFlowsSelection(node: DefaultMutableTreeNode?) {
        when (val obj = node?.userObject) {
            is TaintStep -> {
                val key = stepKeyOf(node)
                if (key != currentStepKey) {
                    currentStepKey = key
                    stepOriginalExpanded = false
                }
                val explanation = key?.let { cachedStepExplanations[it] }
                renderFlowsDescriptionPane(
                    explanation = explanation,
                    originalMessage = obj.message,
                    isStep = true,
                )
            }
            is TraceNodeEntry -> {
                currentStepKey = null
                renderFlowsDescriptionPane(
                    explanation = obj.title,
                    originalMessage = null,
                    isStep = false,
                )
            }
            else -> {
                currentStepKey = null
                flowsSplitter.secondComponent = null
                flowsDescriptionArea.text = ""
            }
        }
    }

    /** Computes (traceIndex, stepIndex) for a flows-tree leaf node, both 0-based. */
    private fun stepKeyOf(stepNode: DefaultMutableTreeNode): Pair<Int, Int>? {
        val traceNode = stepNode.parent as? DefaultMutableTreeNode ?: return null
        val rootOfFlows = traceNode.parent as? DefaultMutableTreeNode ?: return null
        val traceIdx = rootOfFlows.getIndex(traceNode)
        val stepIdx = traceNode.getIndex(stepNode)
        if (traceIdx < 0 || stepIdx < 0) return null
        return Pair(traceIdx, stepIdx)
    }

    private fun renderFlowsDescriptionPane(explanation: String?, originalMessage: String?, isStep: Boolean) {
        val showLLM = !explanation.isNullOrBlank()
        val showOriginal = isStep && !originalMessage.isNullOrBlank()
        if (!showLLM && !showOriginal) {
            flowsSplitter.secondComponent = null
            flowsDescriptionArea.text = ""
            return
        }
        flowsSplitter.secondComponent = flowsDescriptionScroll
        flowsDescriptionArea.text = renderFlowsHtml(explanation, originalMessage, isStep)
        flowsDescriptionArea.caretPosition = 0
    }

    private fun renderFlowsHtml(explanation: String?, originalMessage: String?, isStep: Boolean): String {
        val font = UIUtil.getLabelFont()
        val fgHex = colorToHex(UIUtil.getLabelForeground())
        val mutedHex = colorToHex(UIUtil.getContextHelpForeground())
        val linkHex = colorToHex(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        return buildString {
            appendLine(
                """
                |<html><head><style>
                |    body { margin: 0; padding: 12px;
                |        font-family: '${font.family}', sans-serif;
                |        font-size: ${font.size}pt; color: $fgHex; line-height: 1.5; }
                |    .toggle-row { margin: 8px 0 0 0; }
                |    .original { color: $mutedHex; line-height: 1.5; margin: 6px 0 0 0; }
                |    .muted { color: $mutedHex; }
                |    a { color: $linkHex; text-decoration: none; }
                |</style></head><body>
                """.trimMargin()
            )
            if (isStep && !originalMessage.isNullOrBlank()) {
                val toggleLabel = if (stepOriginalExpanded) "Hide original finding message" else "Show original finding message"
                appendLine("""<p class="toggle-row" style="margin-top:0;"><a href="#toggle-step-original">$toggleLabel</a></p>""")
                if (stepOriginalExpanded) {
                    appendLine("""<div class="original">${originalMessage.escapeHtml().toInlineHtml()}</div>""")
                }
            }
            val expl = explanation?.takeIf { it.isNotBlank() }
            if (expl != null) {
                appendLine("<p>${Glossary.annotate(expl.escapeHtml().toInlineHtml())}</p>")
            } else if (isStep) {
                appendLine("""<p class="muted" style="font-style:italic;">No explanation for this step yet.</p>""")
            }
            appendLine("</body></html>")
        }
    }

    /**
     * Extracts `**STEP T.S**: ...` blocks from a raw LLM response into a (trace,step) → text
     * map (both indices 0-based). Each block ends at the next `**Marker**` sequence or end of
     * response. Returns empty when the response has no STEP markers.
     */
    private fun parseStepExplanations(response: String): Map<Pair<Int, Int>, String> {
        if (response.isBlank()) return emptyMap()
        val stepPattern = Regex("""\*\*STEP\s+(\d+)\.(\d+)\*\*\s*:?\s*""", RegexOption.IGNORE_CASE)
        val anyMarker = Regex("""\*\*[A-Za-z]""")
        val matches = stepPattern.findAll(response).toList()
        val out = mutableMapOf<Pair<Int, Int>, String>()
        for (m in matches) {
            val traceIdx = m.groupValues[1].toInt() - 1
            val stepIdx = m.groupValues[2].toInt() - 1
            if (traceIdx < 0 || stepIdx < 0) continue
            val start = m.range.last + 1
            val nextMarker = anyMarker.find(response, start)
            val end = nextMarker?.range?.first ?: response.length
            val text = response.substring(start, end).trim()
            if (text.isNotEmpty()) out[Pair(traceIdx, stepIdx)] = text
        }
        return out
    }

    private fun installTreePopup() {
        val action = ActionManager.getInstance().getAction("Safe.ExplainVulnerabilityAction") ?: return
        val group = DefaultActionGroup().apply { add(action) }
        PopupHandler.installPopupMenu(tree, group, "SafeTreePopup")
    }

    private fun loadStoredFindings() {
        val storage = ExplanationStorageService.getInstance(project)
        val snapshotFindings = FindingsSnapshotService.getInstance(project).loadFindings().orEmpty()

        // If the panel opens before SafeStartupActivity has populated storage, mirror the
        // snapshot into storage now so VulnerabilityEditorListener can re-apply highlights
        // when files open later in this session.
        for (vuln in snapshotFindings) {
            if (vuln.filePath == null || vuln.startLine == null) continue
            val alreadyStored = storage.getForFile(vuln.filePath).any { it.vulnerability == vuln }
            if (!alreadyStored) {
                storage.store(ExplanationStorageService.ExplanationEntry(vuln, ""))
            }
        }

        val findings = storage.getAll().map { it.vulnerability }
        if (findings.isNotEmpty()) addFindings(findings)
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

            // Cache stores cwe as a plain id string — re-enrich with the friendly name from CWE_MAPPING
            val cwe = cached.cwe?.let { QodanaNodeExtractor.cweFromTagString(it) }
            val meta = findingMetaFor(cached.inspectionId, cached.fileName)
            val entry = ExplanationTreeEntry(
                cached.inspectionId, cwe, cached.fileName, "", cached.response,
                sastMessage = meta?.message,
                severity = meta?.severity,
            )
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

    /**
     * Bulk-insert findings without explanations. The tree groups them by CWE → directory → file
     * and the detail pane shows a placeholder until the user runs "Explain Vulnerability" on one.
     * Returns the number of newly-inserted entries (duplicates are skipped).
     */
    fun addFindings(findings: List<VulnerabilityInfo>): Int {
        val cache = ExplanationCacheService.getInstance(project)
        var inserted = 0
        for (vuln in findings) {
            val id = vuln.inspectionId ?: "Unknown"
            val cwe = vuln.cwe ?: Cwe(id = id)
            val key = findingKey(id, vuln.filePath, vuln.startLine, vuln.endLine)
            if (explanations.containsKey(key)) continue
            // If this finding already has an explanation in the cache (loaded into the tree
            // by loadCachedExplanations), don't add a separate placeholder entry for it.
            if (vuln.inspectionId != null && vuln.filePath != null
                && cache.find(vuln.inspectionId, vuln.filePath) != null) continue

            val entry = ExplanationTreeEntry(
                inspectionId = id,
                cwe = cwe,
                filePath = vuln.filePath,
                htmlExplanation = "",
                rawResponse = "",
                startLine = vuln.startLine,
                endLine = vuln.endLine,
                sastMessage = vuln.message,
                severity = vuln.severity,
            )
            explanations[key] = entry
            insertEntryIntoTree(entry)
            inserted++
        }
        if (inserted > 0) {
            treeModel.reload(rootNode)
            tree.expandRow(0)
            updateEmptyState()
        }
        return inserted
    }

    private fun findingKey(inspectionId: String, filePath: String?, startLine: Int?, endLine: Int?): String =
        "$inspectionId::${filePath ?: ""}::${startLine ?: 0}::${endLine ?: 0}"

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
            appendLine(htmlHead())

            val cweLabel = if (cweName.isNotBlank()) "${cweId.escapeHtml()} ${cweName.escapeHtml()}" else cweId.escapeHtml()
            val llmTitle = sections["title"]?.takeIf { it.isNotBlank() }?.escapeHtml()
            if (llmTitle != null) {
                appendLine("""<h1>$llmTitle</h1>""")
                // Pull the curated short name from CWE_MAPPING (ignoring whatever long
                // description might be sitting in entry.cwe.name from a Semgrep tag).
                val shortName = entry.cwe?.id?.let {
                    QodanaNodeExtractor.cweFromTagString(it).name?.takeIf { n -> n.isNotBlank() }
                }
                val badgeUrl = CweBadge.urlFor(entry.cwe?.id, shortName)
                if (badgeUrl != null) {
                    appendLine("""<p class="cwe-line"><img src="$badgeUrl"/></p>""")
                } else if (cweLabel.isNotBlank()) {
                    appendLine("""<p class="cwe-tag">$cweLabel</p>""")
                }
            } else {
                // Cached or older response without a TITLE section — fall back to the CWE.
                appendLine("""<h1>$cweLabel</h1>""")
            }

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
                appendLine(
                    """<div class="section">
                        <p class="section-title">$title</p>
                        <div class="section-body">${Glossary.annotate(body.markdownToHtml())}</div>
                    </div>""".trimIndent()
                )
            }

            if (entry.rawResponse.isBlank()) {
                appendLine("""<p class="muted" style="font-style:italic; margin-top:14px;">No explanation yet. Right-click the finding and run <b>Explain Vulnerability</b> to generate one.</p>""")
            } else {
                if (tldrText.isNotBlank()) {
                    appendLine("""<p class="tldr"><b>TL;DR:</b> ${Glossary.annotate(tldrText.escapeHtml().toInlineHtml())}</p>""")
                }
                appendSection("What", whatText)
                appendSection("Why", whyText)
                appendSection("Where", whereText)
                appendSection("How to fix", howText)
                if (deepDiveText.isNotBlank()) {
                    val toggleLabel = if (deepDiveExpanded) "Hide deep dive" else "Show deep dive"
                    appendLine("""<p class="toggle-row"><a href="#toggle-deepdive">$toggleLabel</a></p>""")
                    if (deepDiveExpanded) {
                        appendLine("""<div class="deep-dive">${Glossary.annotate(deepDiveText.markdownToHtml())}</div>""")
                    }
                }
            }

            appendLine("</body></html>")
        }
    }

    /**
     * Walks the HTMLDocument element tree at the cursor position looking for a `title`
     * attribute on any ancestor. Used to surface `<abbr title="...">` glossary tooltips,
     * which JEditorPane otherwise ignores.
     */
    private fun htmlTitleAt(pane: JEditorPane, event: MouseEvent): String? {
        val doc = pane.document as? javax.swing.text.html.HTMLDocument ?: return null
        val pos = pane.viewToModel2D(event.point)
        if (pos < 0) return null
        var elem: javax.swing.text.Element? = doc.getCharacterElement(pos)
        while (elem != null) {
            val attrs = elem.attributes
            val names = attrs.attributeNames
            while (names.hasMoreElements()) {
                val name = names.nextElement()
                val value = attrs.getAttribute(name)
                if (value is javax.swing.text.AttributeSet) {
                    val title = value.getAttribute(javax.swing.text.html.HTML.Attribute.TITLE)
                    if (title is String && title.isNotEmpty()) return title
                }
            }
            val direct = attrs.getAttribute(javax.swing.text.html.HTML.Attribute.TITLE)
            if (direct is String && direct.isNotEmpty()) return direct
            elem = elem.parentElement
        }
        return null
    }

    private fun colorToHex(color: Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    private fun buildSideToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            ActionManager.getInstance().getAction("Safe.ImportAllProblemsAction")?.let { add(it) }
            addSeparator()
            add(object : AnAction("Expand All", "Expand all nodes", AllIcons.Actions.Expandall) {
                override fun actionPerformed(e: AnActionEvent) = expandAllNodes()
            })
            add(object : AnAction("Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall) {
                override fun actionPerformed(e: AnActionEvent) = collapseAllNodes()
            })
            addSeparator()
            add(object : AnAction(
                "SAFE Settings",
                "Configure the LLM provider, endpoint, model, and API key",
                AllIcons.General.Settings,
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        de.fraunhofer.iem.safe.settings.SafeSettingsConfigurable::class.java,
                    )
                }
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("SafeToolWindowSideBar", group, false)
        toolbar.targetComponent = this
        return toolbar.component
    }

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
                    icon = severityIcon(obj.severity) ?: AllIcons.Nodes.Class
                }
            }

            return component
        }

        private fun severityIcon(severity: String?): javax.swing.Icon? = when (severity?.lowercase()) {
            "error", "critical", "high" -> AllIcons.General.Error
            "warning", "warn", "medium", "moderate" -> AllIcons.General.Warning
            "note", "info", "informational", "low" -> AllIcons.General.Information
            else -> null
        }
    }

    /** Renders trace headers in bold and step rows as `<message>  ClassName:line` (location greyed). */
    private class FlowsTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                is TraceNodeEntry -> {
                    append(shortenFilePathsInText(obj.title), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is TaintStep -> {
                    val parent = node.parent as? DefaultMutableTreeNode
                    val total = parent?.childCount ?: 0
                    val index = parent?.let { it.getIndex(node) } ?: 0
                    icon = stepIcon(obj.message, index, total)
                    val message = obj.message?.takeIf { it.isNotBlank() }
                    if (message != null) {
                        // Message text already carries the location (e.g. "Source: 'query' @ 'ClassName:193'"),
                        // so the trailing grey suffix would duplicate it.
                        append(shortenFilePathsInText(message), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    } else {
                        val location = buildString {
                            obj.filePath?.let { append(classNameOf(it)) }
                            obj.startLine?.let { append(":").append(it) }
                        }
                        if (location.isNotEmpty()) {
                            append(location, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                }
                else -> append(obj?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        /** Picks an icon for a step using [StepRoleResolver] so colors stay in sync with editor highlights. */
        private fun stepIcon(message: String?, index: Int, total: Int): javax.swing.Icon? =
            when (StepRoleResolver.resolve(message, index, total)) {
                StepRole.SOURCE -> TaintFlowIcons.SOURCE
                StepRole.SINK -> TaintFlowIcons.SINK
                StepRole.CALL -> TaintFlowIcons.CALL
                StepRole.PROPAGATOR -> TaintFlowIcons.PROPAGATOR
                StepRole.SANITIZER -> TaintFlowIcons.SANITIZER
                StepRole.UNKNOWN -> null
            }

        /** Last path segment with the file extension removed (e.g. `src/.../UserService.java` → `UserService`). */
        private fun classNameOf(path: String): String {
            val fileName = path.substringAfterLast('/').substringAfterLast('\\')
            val dot = fileName.lastIndexOf('.')
            return if (dot > 0) fileName.substring(0, dot) else fileName
        }

        /** Replaces any `path/to/File.ext` occurrence in [text] with the bare class name (`File`). */
        private fun shortenFilePathsInText(text: String): String =
            Regex("[A-Za-z0-9_./\\\\\\-]+\\.[A-Za-z][A-Za-z0-9]*").replace(text) { match ->
                classNameOf(match.value)
            }
    }
}