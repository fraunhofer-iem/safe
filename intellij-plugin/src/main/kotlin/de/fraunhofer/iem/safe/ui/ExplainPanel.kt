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
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.fraunhofer.iem.safe.llm.SafeLlmSettings
import de.fraunhofer.iem.safe.llm.SafeProviderChangeListener
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.HyperlinkEvent
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
    val rawResponse: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    /** The original SAST tool's message — shown in the detail pane when no LLM explanation exists yet. */
    val sastMessage: String? = null,
    /** SARIF level (`error`, `warning`, `note`) or Qodana severity. Used for the severity badge. */
    val severity: String? = null,
)

data class CweNodeEntry(val cwe: Cwe, var count: Int = 0)
data class DirectoryNodeEntry(val path: String)
data class StatusGroupEntry(val explained: Boolean) {
    val title: String get() = if (explained) "Explained" else "Not explained"
}

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

    /** The leaf currently rendered in the detail pane. Used to re-render on toggle. */
    private var currentEntry: ExplanationTreeEntry? = null

    /** Per-detail-pane toggle for the "original finding message" disclosure. Resets on selection change. */
    private var sastMessageExpanded: Boolean = false

    /** Toggle for the "Deep dive" disclosure in the leaf detail. Resets on selection change. */
    private var deepDiveExpanded: Boolean = false

    /** Per-step toggle for the flow-pane "original finding message" disclosure. Resets on selection change. */
    private var stepOriginalExpanded: Boolean = false

    /** (traceIndex, stepIndex) of the currently-highlighted step row in the flows tree, 0-based. */
    private var currentStepKey: Pair<Int, Int>? = null

    /** Parsed `**STEP T.S**` explanations from [currentEntry]'s LLM response, refreshed whenever a finding is loaded. */
    private var cachedStepExplanations: Map<Pair<Int, Int>, String> = emptyMap()

    private val contentArea = object : JEditorPane() {
        override fun getToolTipText(event: MouseEvent): String? = htmlTitleAt(this, event)
    }.apply {
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
        add(JBScrollPane(contentArea).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }



    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Filter findings (file, rule, CWE)"
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
        innerComponent = detailPanel
        firstSize = JBUI.scale(300)
        lastSize = JBUI.scale(320)
    }

    private data class TraceNodeEntry(val title: String, val trace: TaintTrace)

    private data class FindingMeta(
        val message: String?,
        val ruleName: String?,
        val severity: String?,
        val startLine: Int?,
        val endLine: Int?,
    )

    /**
     * Cache-loaded entries carry only the LLM response. Look up the rest of the finding's
     * metadata (original SAST message, rule name, severity) from the persisted snapshot.
     *
     * Declared before `init { }` so the lazy backing field is initialised in time for
     * `loadCachedExplanations()`, which is called from the init block.
     */
    private val findingMetaMap: Map<Pair<String, String>, FindingMeta> by lazy {
        FindingsSnapshotService.getInstance(project).loadFindings()
            ?.mapNotNull { v ->
                val id = v.inspectionId ?: return@mapNotNull null
                val path = v.filePath ?: return@mapNotNull null
                Pair(id, path) to FindingMeta(
                    message = v.message,
                    ruleName = v.inspectionName ?: v.inspectionId,
                    severity = v.severity,
                    startLine = v.startLine,
                    endLine = v.endLine,
                )
            }
            ?.toMap()
            ?: emptyMap()
    }

    private fun findingMetaFor(inspectionId: String?, filePath: String?): FindingMeta? {
        if (inspectionId == null || filePath == null) return null
        return findingMetaMap[Pair(inspectionId, filePath)]
    }

    companion object {
        val SAFE_TREE_ENTRY_KEY: DataKey<ExplanationTreeEntry> = DataKey.create("SafeTree.SelectedEntry")

        private val UNMAPPED_CWE = Cwe(id = "(unmapped)", name = "Findings without a CWE")

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
        add(buildSideToolbar(), BorderLayout.WEST)
        add(splitter, BorderLayout.CENTER)

        tree.addTreeSelectionListener {
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                ?: return@addTreeSelectionListener
            when (val userObject = selectedNode.userObject) {
                is ExplanationTreeEntry -> {
                    if (userObject != currentEntry) {
                        currentEntry = userObject
                        sastMessageExpanded = false
                        deepDiveExpanded = false
                    }
                    setHtmlContent(formatEntryAsHtml(userObject))
                    val traces = lookupTraces(userObject)
                    updateFlowsPane(traces)
                    VulnerabilityHighlightService.getInstance(project)
                        .applyTraceHighlights(project, traces, cachedStepExplanations)
                    FindingsSnapshotService.getInstance(project).lastSelectedKey =
                        findingKey(userObject.inspectionId, userObject.filePath, userObject.startLine, userObject.endLine)
                }
                is CweNodeEntry -> {
                    currentEntry = null
                    setHtmlContent(formatCweAsHtml(userObject, selectedNode))
                    updateFlowsPane(emptyList())
                    VulnerabilityHighlightService.getInstance(project).clearTraceHighlights()
                }
            }
        }

        // Double-click a finding leaf to jump to its line in the editor.
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val entry = node.userObject as? ExplanationTreeEntry ?: return
                navigateToEntry(entry)
            }
        })

        // Single-click a trace or step to reveal the explanation pane below the flows tree.
        flowsTree.addTreeSelectionListener {
            val selectedNode = flowsTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            handleFlowsSelection(selectedNode)
        }

        // Double-click a step in the flows pane to jump to that step's location.
        flowsTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val path = flowsTree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val step = node.userObject as? TaintStep ?: return
                navigateToStep(step)
            }
        })

        loadCachedExplanations()
        loadStoredFindings()
        maybeRefreshFromQodana()
        restoreLastSelection()
        installTreePopup()

        // Refresh the tree whenever the user switches LLM provider in settings — each
        // provider has its own slice of the explanation cache, so the tree must rebuild.
        val bus = ApplicationManager.getApplication().messageBus.connect(project)
        bus.subscribe(SafeProviderChangeListener.TOPIC, SafeProviderChangeListener {
            ApplicationManager.getApplication().invokeLater { reloadForActiveProvider() }
        })

        // The detail pane is HTML with colors and font sizes baked into the document at
        // render time, so theme/font changes don't propagate automatically. Re-render
        // when the LaF flips (Darcula ↔ light) or when the user adjusts the IDE-wide
        // font/scaling so the panel stays in sync.
        bus.subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC, com.intellij.ide.ui.LafManagerListener {
            ApplicationManager.getApplication().invokeLater { reapplyAppearance() }
        })
        bus.subscribe(com.intellij.ide.ui.UISettingsListener.TOPIC, com.intellij.ide.ui.UISettingsListener {
            ApplicationManager.getApplication().invokeLater { reapplyAppearance() }
        })

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

    /**
     * If the persisted snapshot was sourced from Qodana, compares its fingerprint to the
     * current Qodana tool window contents. When Qodana has different findings, replaces
     * the storage + snapshot + tree with the live data. When Qodana isn't yet loaded
     * ([QodanaNodeExtractor.extractAll] returns empty), keeps the snapshot.
     */
    private fun maybeRefreshFromQodana() {
        val snapshot = FindingsSnapshotService.getInstance(project)
        if (snapshot.source != FindingsSnapshotService.Source.QODANA) return
        val live = QodanaNodeExtractor.extractAll(project)
        if (live.isEmpty()) return
        if (FindingsSnapshotService.fingerprintOf(live) == snapshot.fingerprint) return

        val storage = ExplanationStorageService.getInstance(project)
        storage.clear()
        for (finding in live) {
            if (finding.filePath == null || finding.startLine == null) continue
            storage.store(ExplanationStorageService.ExplanationEntry(finding, ""))
        }
        snapshot.save(FindingsSnapshotService.Source.QODANA, live)

        // Rebuild tree from cache + new storage.
        rootNode.removeAllChildren()
        explanations.clear()
        treeModel.reload(rootNode)
        loadCachedExplanations()
        loadStoredFindings()
    }

    // ── Tree Structure Helpers ────────────────────────────────────────────────

    private fun insertEntryIntoTree(entry: ExplanationTreeEntry) {
        val cwe = resolveCweForEntry(entry)
        val (dirPath, _) = splitPath(entry.filePath ?: "")
        val explained = entry.rawResponse.isNotBlank()

        val groupNode = findOrCreateStatusGroupNode(explained)
        val cweNode = findOrCreateCweNode(groupNode, cwe)
        (cweNode.userObject as CweNodeEntry).count++

        if (dirPath.isBlank()) {
            cweNode.add(DefaultMutableTreeNode(entry))
        } else {
            val dirNode = findOrCreateDirNode(cweNode, dirPath)
            dirNode.add(DefaultMutableTreeNode(entry))
        }
    }

    /** "Explained" appears at the top, "Not explained" below. */
    private fun findOrCreateStatusGroupNode(explained: Boolean): DefaultMutableTreeNode {
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val entry = child.userObject as? StatusGroupEntry ?: continue
            if (entry.explained == explained) return child
        }
        val node = DefaultMutableTreeNode(StatusGroupEntry(explained))
        val targetIndex = if (explained) 0 else rootNode.childCount
        rootNode.insert(node, minOf(targetIndex, rootNode.childCount))
        return node
    }

    private fun findOrCreateCweNode(parent: DefaultMutableTreeNode, cwe: Cwe): DefaultMutableTreeNode {
        val enriched = enrichCwe(cwe)
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            val nodeEntry = child.userObject as? CweNodeEntry ?: continue
            if (cwesMatch(nodeEntry.cwe, enriched)) {
                val nameUpgrade = nodeEntry.cwe.name == null && enriched.name != null
                val descUpgrade = nodeEntry.cwe.description == null && enriched.description != null
                if (nameUpgrade || descUpgrade) {
                    val merged = nodeEntry.cwe.copy(
                        name = nodeEntry.cwe.name ?: enriched.name,
                        description = nodeEntry.cwe.description ?: enriched.description,
                    )
                    child.userObject = nodeEntry.copy(cwe = merged)
                    treeModel.nodeChanged(child)
                }
                return child
            }
        }
        return DefaultMutableTreeNode(CweNodeEntry(enriched, 0)).also { parent.add(it) }
    }

    private fun enrichCwe(cwe: Cwe): Cwe {
        if (cwe.name != null) return cwe
        val id = cwe.id ?: return cwe
        if (!id.startsWith("CWE-", ignoreCase = true)) return cwe
        return QodanaNodeExtractor.cweFromTagString(id)
    }

    private fun resolveCweForEntry(entry: ExplanationTreeEntry): Cwe =
        entry.cwe
            ?: QodanaNodeExtractor.inspectionIdToCwe(entry.inspectionId)
            ?: UNMAPPED_CWE

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

    private fun findFileNode(matcher: (ExplanationTreeEntry) -> Boolean): DefaultMutableTreeNode? {
        for (g in 0 until rootNode.childCount) {
            val groupNode = rootNode.getChildAt(g) as DefaultMutableTreeNode
            if (groupNode.userObject !is StatusGroupEntry) continue
            for (i in 0 until groupNode.childCount) {
                val cweNode = groupNode.getChildAt(i) as DefaultMutableTreeNode
                for (j in 0 until cweNode.childCount) {
                    val dirNode = cweNode.getChildAt(j) as DefaultMutableTreeNode
                    for (k in 0 until dirNode.childCount) {
                        val fileNode = dirNode.getChildAt(k) as DefaultMutableTreeNode
                        val obj = fileNode.userObject as? ExplanationTreeEntry ?: continue
                        if (matcher(obj)) return fileNode
                    }
                }
            }
        }
        return null
    }

    /** Strict match on the full `(id, file, start, end)` finding key. */
    private fun findFileNodeByKey(key: String): DefaultMutableTreeNode? = findFileNode { obj ->
        findingKey(obj.inspectionId, obj.filePath, obj.startLine, obj.endLine) == key
    }

    private fun selectEntryInTree(entry: ExplanationTreeEntry) {
        val key = findingKey(entry.inspectionId, entry.filePath, entry.startLine, entry.endLine)
        val fileNode = findFileNodeByKey(key) ?: return
        val path = TreePath(fileNode.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    /**
     * Re-selects the leaf the user had selected in the previous IDE session, if it still
     * exists in the tree. Setting `tree.selectionPath` auto-expands ancestor nodes, so the
     * user lands exactly where they left off.
     */
    private fun restoreLastSelection() {
        val key = FindingsSnapshotService.getInstance(project).lastSelectedKey ?: return
        val fileNode = findFileNodeByKey(key) ?: return
        val path = TreePath(fileNode.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    /**
     * Public navigation entry point — selects the first SAFE tree row matching the given
     * `(inspectionId, filePath)` pair. Multiple findings can share that pair (different
     * line ranges); gutter-click navigation just lands on the first occurrence.
     */
    fun selectByInspection(inspectionId: String?, filePath: String?) {
        if (inspectionId == null) return
        val fileNode = findFileNode { obj ->
            obj.inspectionId == inspectionId && obj.filePath == filePath
        } ?: return
        val path = TreePath(fileNode.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        tree.requestFocus()
    }

    private fun updateEntryInTree(entry: ExplanationTreeEntry, key: String) {
        val fileNode = findFileNodeByKey(key) ?: return
        val targetExplained = entry.rawResponse.isNotBlank()

        // Locate the containing StatusGroupEntry by walking up the parents.
        val dirNode = fileNode.parent as? DefaultMutableTreeNode
        val cweNode = dirNode?.parent as? DefaultMutableTreeNode
        val groupNode = cweNode?.parent as? DefaultMutableTreeNode
        val groupEntry = groupNode?.userObject as? StatusGroupEntry

        if (groupEntry?.explained == targetExplained) {
            // No status change — update in place.
            fileNode.userObject = entry
            treeModel.nodeChanged(fileNode)
            return
        }

        // Status changed: detach from the old hierarchy and re-insert via the normal path.
        if (dirNode != null) dirNode.remove(fileNode)
        if (cweNode != null) {
            (cweNode.userObject as? CweNodeEntry)?.let { it.count-- }
            if (dirNode != null && dirNode.childCount == 0) cweNode.remove(dirNode)
            if (cweNode.childCount == 0 && groupNode != null) {
                groupNode.remove(cweNode)
                if (groupNode.childCount == 0) rootNode.remove(groupNode)
            }
        }
        insertEntryIntoTree(entry)
        treeModel.reload(rootNode)
    }


    // ── Public API ────────────────────────────────────────────────────────────

    private fun loadCachedExplanations() {
        val cache = ExplanationCacheService.getInstance(project)
        val provider = activeProviderId()
        for (cached in cache.getAllForProvider(provider)) {

            // Cache stores cwe as a plain id string — re-enrich with the friendly name from CWE_MAPPING
            val cwe = cached.cwe?.let { QodanaNodeExtractor.cweFromTagString(it) }
            val meta = findingMetaFor(cached.inspectionId, cached.fileName)
            // Prefer the line range the cache itself recorded; fall back to the snapshot
            // for entries written before line-aware caching existed.
            val startLine = cached.startLine ?: meta?.startLine
            val endLine = cached.endLine ?: meta?.endLine
            val entry = ExplanationTreeEntry(
                cached.inspectionId, cwe, cached.fileName, "", cached.response,
                startLine = startLine,
                endLine = endLine,
                sastMessage = meta?.message,
                severity = meta?.severity,
            )
            val key = findingKey(cached.inspectionId, cached.fileName, startLine, endLine)
            explanations[key] = entry
            insertEntryIntoTree(entry)
        }
        if (rootNode.childCount > 0) {
            treeModel.reload(rootNode)
            tree.expandRow(0)
        }
        updateEmptyState()
    }

    private fun activeProviderId(): String = SafeLlmSettings.getInstance().providerKind.id

    /**
     * Tear down the tree, in-memory entry map, detail pane, and source-editor highlights,
     * then rebuild from the persistent cache + storage using the now-active provider id.
     * Called when the user changes providers in Settings.
     */
    private fun reloadForActiveProvider() {
        rootNode.removeAllChildren()
        explanations.clear()
        currentEntry = null
        cachedStepExplanations = emptyMap()
        treeModel.reload(rootNode)
        setHtmlContent("")
        updateFlowsPane(emptyList())
        VulnerabilityHighlightService.getInstance(project).clearTraceHighlights()

        loadCachedExplanations()
        loadStoredFindings()
        restoreLastSelection()
        updateEmptyState()
    }

    /**
     * Repaints the JEditorPane-backed detail panes after a LaF or font/scaling change.
     * The HTML content baked into those panes carries inline color and font-size values,
     * so the only way to pick up the new theme/font is to re-emit the HTML.
     */
    private fun reapplyAppearance() {
        contentArea.background = UIUtil.getPanelBackground()
        flowsDescriptionArea.background = UIUtil.getPanelBackground()
        currentEntry?.let { setHtmlContent(formatEntryAsHtml(it)) }
        // Re-render the per-step pane as well so its colors/fonts refresh in lock-step.
        handleFlowsSelection(flowsTree.lastSelectedPathComponent as? DefaultMutableTreeNode)
        // Tree cells repaint themselves on LaF change via Swing's standard repaint chain;
        // a forced revalidate here just makes the side toolbar / search field follow suit.
        revalidate()
        repaint()
    }

    fun showCachedIfAvailable(
        inspectionId: String?,
        fileName: String?,
        startLine: Int?,
        endLine: Int?,
    ): Boolean {
        val id = inspectionId ?: return false
        val key = findingKey(id, fileName, startLine, endLine)
        val entry = explanations[key] ?: return false
        setHtmlContent(formatEntryAsHtml(entry))
        selectEntryInTree(entry)
        return true
    }

    /**
     * @param cwe  Full [Cwe] object (id + optional human-readable name).
     *
     * Two findings with the same `(inspectionId, fileName)` but different line ranges are
     * stored as separate cache entries — that lets the user explain each occurrence of,
     * say, CWE-502 in the same file independently instead of all sharing one explanation.
     */
    fun showExplanation(
        inspectionId: String?,
        cwe: Cwe?,
        fileName: String?,
        response: String,
        startLine: Int?,
        endLine: Int?,
    ) {
        val id = inspectionId ?: "Unknown vulnerability"
        val severity = findingMetaFor(id, fileName)?.severity
        val entry = ExplanationTreeEntry(
            id, cwe, fileName, "", response,
            startLine = startLine, endLine = endLine,
            severity = severity,
        )
        val key = findingKey(id, fileName, startLine, endLine)

        // The active provider id + line range scope the entry, so switching providers or
        // explaining a different occurrence of the same rule doesn't overwrite this one.
        ExplanationCacheService.getInstance(project)
            .store(id, cwe?.id, fileName, startLine, endLine, response, activeProviderId())

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
            // Scoped to the active provider — the same finding can legitimately appear as
            // "Not explained" under provider B while having an entry under provider A.
            if (vuln.inspectionId != null && vuln.filePath != null
                && cache.find(vuln.inspectionId, vuln.filePath, vuln.startLine, vuln.endLine, activeProviderId()) != null) continue

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
        val pattern = Regex("""\*\*(TITLE|TLDR|WHAT|WHERE|WHY|HOW|DEEPDIVE)\*\*\s*:?\s*""", RegexOption.IGNORE_CASE)
        // Bound each section at the next `**Marker**` of any kind so STEP blocks (or
        // anything else the LLM adds after WHAT/WHERE/WHY/HOW) don't get absorbed into
        // the last-matched section.
        val anyMarker = Regex("""\*\*[A-Za-z]""")
        for (match in pattern.findAll(response)) {
            val key = match.groupValues[1].lowercase()
            val start = match.range.last + 1
            val end = anyMarker.find(response, start)?.range?.first ?: response.length
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

    /** Shared `<html><head><style>` block — keeps both detail views visually consistent. */
    private fun htmlHead(): String {
        val fgHex = colorToHex(UIUtil.getLabelForeground())
        val mutedHex = colorToHex(UIUtil.getContextHelpForeground())
        val linkHex = colorToHex(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        val codeBgHex = colorToHex(adjustBrightness(UIUtil.getPanelBackground(), if (UIUtil.isUnderDarcula()) 22 else -14))
        // JBFont.label() respects IDE-wide font scaling (Settings → Appearance, plus the
        // Cmd+/Cmd− / "Increase IDE Font" actions), which UIUtil.getLabelFont() does not
        // pick up consistently. The detail pane re-renders on UISettings changes so this
        // value stays in sync after the user rescales mid-session.
        val font = com.intellij.util.ui.JBFont.label()
        val fs = font.size
        return """
            <html><head><style>
                body { margin: 0; padding: 14px 16px;
                    font-family: '${font.family}', sans-serif;
                    font-size: ${fs}pt; color: $fgHex; }
                h1 { font-size: ${fs + 2}pt; font-weight: bold; margin: 0 0 2px 0; color: $fgHex; }
                .cwe-tag { color: $mutedHex; font-size: ${fs - 1}pt; margin: 6px 0 0 0; }
                .cwe-line { margin: 6px 0 0 0; }
                .filepath { color: $mutedHex; font-size: ${fs - 1}pt; font-family: monospace;
                    margin: 4px 0 0 0; }
                .toggle-row { margin: 6px 0 0 0; }
                .tldr { margin: 14px 0 0 0; line-height: 1.5; font-style: italic; color: $fgHex; }
                .deep-dive { margin: 6px 0 0 0; line-height: 1.5; }
                h4 { font-weight: bold; font-size: ${fs}pt; color: $fgHex;
                    margin: 12px 0 2px 0; padding: 0; border-bottom: 0; }
                .sast-message { margin: 6px 0 0 0; color: $mutedHex; }
                .sast-meta { font-weight: bold; color: $mutedHex; }
                .sast-text { line-height: 1.5; margin: 4px 0 0 0; color: $mutedHex; }
                .section { margin-top: 16px; }
                .section-title { font-weight: bold; margin: 0 0 4px 0; color: $fgHex; }
                .section-body { line-height: 1.5; margin: 0; color: $fgHex; }
                .muted { color: $mutedHex; }
                .glossary { color: $linkHex; text-decoration: underline; }
                a { color: $linkHex; text-decoration: none; }
                pre { background-color: $codeBgHex; padding: 6px 10px;
                    margin: 6px 0; font-family: monospace; font-size: ${fs - 1}pt; }
                code { font-family: monospace; }
            </style></head><body>
        """.trimIndent()
    }

    private fun String.toInlineHtml(): String =
        replace("\n\n", "<br/><br/>").replace("\n", "<br/>")

    /**
     * Renders the subset of Markdown the LLM tends to emit:
     *   - fenced code blocks (` ```lang … ``` `) → `<pre><code>…</code></pre>`
     *   - inline code (`` `…` ``) → `<code>…</code>`
     * HTML in the surrounding prose is escaped; newlines become `<br/>`. Code regions are
     * extracted via placeholder tokens so they survive the bulk HTML-escape pass intact.
     */
    private fun String.markdownToHtml(): String {
        val placeholders = mutableListOf<String>()
        fun store(html: String): String {
            val token = " PH${placeholders.size} "
            placeholders.add(html)
            return token
        }
        var s = this
        s = Regex("```(?:[A-Za-z0-9_+#.\\-]*\\s*\\n)?([\\s\\S]*?)```").replace(s) { m ->
            store("<pre><code>${m.groupValues[1].trimEnd().escapeHtml()}</code></pre>")
        }
        s = Regex("`([^`\\n]+)`").replace(s) { m ->
            store("<code>${m.groupValues[1].escapeHtml()}</code>")
        }
        // ATX-style sub-headings (`### Heading`) → <h4>. Used inside the deep dive to
        // break the long write-up into Mechanism / Why naive fixes fail / Edge cases /
        // References. Captured as placeholders BEFORE escapeHtml so the `#` characters
        // don't get HTML-escaped and the heading text doesn't pick up stray `<br/>`.
        s = Regex("(?m)^[ \\t]*###[ \\t]+(.+?)[ \\t]*$").replace(s) { m ->
            store("<h4>${m.groupValues[1].escapeHtml()}</h4>")
        }
        s = s.escapeHtml().replace("\n\n", "<br/><br/>").replace("\n", "<br/>")
        placeholders.forEachIndexed { i, html ->
            s = s.replace(" PH$i ", html)
        }
        return s
    }

    // Implementation moved earlier in the class so the backing lazy field is initialised
    // before `init { loadCachedExplanations() }` runs and asks for it.


    private fun formatEntryAsHtml(entry: ExplanationTreeEntry): String {
        val sections = parseResponseSections(entry.rawResponse)
        val cweId = entry.cwe?.id ?: entry.inspectionId
        val cweName = entry.cwe?.name ?: ""

        val tldrText = sections["tldr"] ?: ""
        val whatText = sections["what"] ?: ""
        val whyText = sections["why"] ?: ""
        val whereText = sections["where"] ?: ""
        val howText = sections["how"] ?: ""
        val deepDiveText = sections["deepdive"] ?: ""

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
                val badgeHtml = CweBadge.htmlFor(entry.cwe?.id, shortName)
                if (badgeHtml != null) {
                    appendLine("""<p class="cwe-line">$badgeHtml</p>""")
                } else if (cweLabel.isNotBlank()) {
                    appendLine("""<p class="cwe-tag">$cweLabel</p>""")
                }
            } else {
                // Cached or older response without a TITLE section — fall back to the CWE.
                appendLine("""<h1>$cweLabel</h1>""")
            }

            // Expandable area with the original SAST tool message — collapsed by default,
            // toggled via an internal hyperlink handled by the panel's HyperlinkListener.
            val meta = findingMetaFor(entry.inspectionId, entry.filePath)
            val sastMessage = (entry.sastMessage ?: meta?.message)?.takeIf { it.isNotBlank() }
            val ruleName = (meta?.ruleName ?: entry.inspectionId).takeIf { it.isNotBlank() }
            if (sastMessage != null) {
                val toggleLabel = if (sastMessageExpanded) "Hide original finding message" else "Show original finding message"
                appendLine("""<p class="toggle-row"><a href="#toggle-sast">$toggleLabel</a></p>""")
                if (sastMessageExpanded) {
                    appendLine("""<div class="sast-message">""")
                    if (ruleName != null) {
                        appendLine("""<div class="sast-meta">${ruleName.escapeHtml()}</div>""")
                    }
                    appendLine("""<div class="sast-text">${sastMessage.escapeHtml().toInlineHtml()}</div>""")
                    appendLine("""</div>""")
                }
            }

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

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun formatCweAsHtml(cweEntry: CweNodeEntry, cweNode: DefaultMutableTreeNode): String {
        val cwe = cweEntry.cwe
        val cweId = cwe.id ?: ""
        val cweName = cwe.name ?: ""
        val tagDescription = cwe.description?.takeIf { it.isNotBlank() }

        return buildString {
            appendLine(htmlHead())

            val title = if (cweName.isNotBlank()) "${cweId.escapeHtml()} ${cweName.escapeHtml()}" else cweId.escapeHtml()
            appendLine("""<h1>$title</h1>""")

            // Description from the bundled MITRE CWE XML catalog; falls back to the verbose
            // tag text if a finding's CWE id isn't in the catalog.
            val descriptionText = CweCatalog.descriptionFor(cweId) ?: tagDescription
            appendLine("""<div class="section">""")
            if (descriptionText != null) {
                appendLine("""<div class="section-body">${descriptionText.escapeHtml()}</div>""")
            } else {
                appendLine("""<p class="muted" style="font-style:italic; margin:0;">No description available.</p>""")
            }
            if (cweId.startsWith("CWE-")) {
                val number = cweId.removePrefix("CWE-")
                appendLine(
                    """<p style="margin-top:6px;">"""
                        + """<a href="https://cwe.mitre.org/data/definitions/$number.html">Read more on cwe.mitre.org &rarr;</a>"""
                        + """</p>"""
                )
            }
            appendLine("""</div>""")

            appendLine("""</body></html>""")
        }
    }


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
            add(de.fraunhofer.iem.safe.actions.SafeProviderComboAction())
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
                        val groupNode = node.getChildAt(i) as? DefaultMutableTreeNode
                        (0 until (groupNode?.childCount ?: 0)).sumOf { j ->
                            ((groupNode?.getChildAt(j) as? DefaultMutableTreeNode)
                                ?.userObject as? CweNodeEntry)?.count ?: 0
                        }
                    }
                    text = "<html><b>$obj</b> <font color='gray'>($total)</font></html>"
                    font = font.deriveFont(Font.BOLD)
                    icon = null
                }
                is StatusGroupEntry -> {
                    val total = (0 until node.childCount).sumOf { i ->
                        ((node.getChildAt(i) as? DefaultMutableTreeNode)
                            ?.userObject as? CweNodeEntry)?.count ?: 0
                    }
                    text = "<html><b>${obj.title}</b> <font color='gray'>($total)</font></html>"
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
                    val base = fileName.ifBlank { obj.filePath ?: obj.inspectionId }
                    val lineSuffix = obj.startLine?.let { ":$it" } ?: ""
                    // The "Explained" / "Not explained" status group above the row already
                    // communicates whether an explanation exists, so don't repeat it here.
                    text = "<html>$base$lineSuffix</html>"
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