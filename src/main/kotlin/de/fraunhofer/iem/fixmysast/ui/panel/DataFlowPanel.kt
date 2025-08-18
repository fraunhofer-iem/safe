package de.fraunhofer.iem.fixmysast.ui.panel

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.fixmysast.PluginBundle
import de.fraunhofer.iem.fixmysast.ui.srm.SrmFinder
import de.fraunhofer.iem.fixmysast.comm.DataflowNotifier
import de.fraunhofer.iem.fixmysast.ui.icons.IconUtils
import de.fraunhofer.iem.fixmysast.sast.DataFlowCategory
import de.fraunhofer.iem.fixmysast.sast.DataFlowElement
import de.fraunhofer.iem.fixmysast.sast.Issue
import de.fraunhofer.iem.fixmysast.ui.icons.PluginIcons
import de.fraunhofer.iem.fixmysast.util.MethodUtil
import java.awt.*
import java.io.File
import javax.swing.*

class DataFlowPanel(val project: Project) : JPanel() {

    val bus: MessageBus = project.messageBus

    private var editor: Editor? = null
    private var dataFlowTrace: List<DataFlowElement>? = null
    private var currentTraceIndex: Int = -1
    private val navigationPanel = JPanel(FlowLayout(FlowLayout.LEFT))
    private val dataFlowPanel = JPanel(BorderLayout()).apply {
    }

    private val dataFlowLabel = JLabel().apply {
    }

    var dataFlowHighlight: RangeHighlighter? = null

    init {

        layout = BorderLayout()



        bus.connect().subscribe(
            DataflowNotifier.SHOW_EDITOR_TOPIC,
            object : DataflowNotifier {

                override fun showEditor(issue: Issue) {
                    showFileContent(issue)
                }
            })
    }

    /***
     * Open issue file in the mini-editor.
     ***/
    fun showFileContent(issue: Issue) {
        removeAll()
        removeEditorIfPresent()

        val relativePath = issue.location.fileName ?: return
        val absolutePath = "${project.basePath}/$relativePath"
        val fileContent = File(absolutePath ?: "").takeIf { it.exists() }?.readText() ?: "File not found."
        dataFlowTrace = issue.dataFlowTrace

        val factory = EditorFactory.getInstance()
        val document: Document = factory.createDocument(fileContent)

        editor = EditorFactory.getInstance().createEditor(document, project).apply {
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isRightMarginShown = true
            settings.additionalLinesCount = 2
        }

        add(editor!!.component, BorderLayout.CENTER)

        if (issue.hasDataFlowTrace) {
            currentTraceIndex = -1
            annotateDataFlow(issue, editor)
            setupNavigationBar()
            add(navigationPanel, BorderLayout.NORTH)
            add(dataFlowPanel, BorderLayout.SOUTH)
        }

        annotateSRM(fileContent)

        revalidate()
        repaint()
    }

    private fun removeEditorIfPresent() {
        editor?.let {
            EditorFactory.getInstance().releaseEditor(it)
            dataFlowLabel.text = ""
            editor = null
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        removeEditorIfPresent()
    }

    /***
     * Annotates the dataflow_trace (if present) in the mini-editor and adds gutter icons.
     ***/
    private fun annotateDataFlow(issue: Issue, editor: Editor?) {
        val markupModel = editor?.markupModel ?: return

        issue.dataFlowTrace?.forEach { dataFlowElement ->
            val highlighter = markupModel.addRangeHighlighter(
                dataFlowElement.startOffset,
                dataFlowElement.endOffset,
                HighlighterLayer.ERROR,
                TextAttributes().apply {
                    effectType = EffectType.LINE_UNDERSCORE
                    effectColor = Color.darkGray
                },
                HighlighterTargetArea.EXACT_RANGE
            )

            highlighter.errorStripeTooltip =
                dataFlowElement.name + " is a " + dataFlowElement.type.toString().lowercase() + "."

            var icon: Icon

            if (dataFlowElement.type.toString().lowercase().contentEquals("source"))
                icon = PluginIcons.SOURCE
            else if (dataFlowElement.type.toString().lowercase().contentEquals("sink"))
                icon = PluginIcons.SINK
            else if (dataFlowElement.type.toString().lowercase().contentEquals("propagator"))
                icon = PluginIcons.PROPAGATOR
            else
                icon = PluginIcons.DEFAULT

            highlighter.gutterIconRenderer = object : GutterIconRenderer() {
                override fun getIcon(): Icon = icon
                override fun getTooltipText(): String? =
                    dataFlowElement.name + " is a " + dataFlowElement.type.toString().lowercase() + "."

                override fun equals(p0: Any?): Boolean = false
                override fun hashCode(): Int = icon.hashCode()
            }
        }
    }

    /***
     * Adds navigation panel and buttons to navigate through the dataflow_trace.
     */
    private fun setupNavigationBar() {
        navigationPanel.removeAll()

        val prevButton = JButton(AllIcons.General.ChevronLeft).apply {
            toolTipText = "Previous"
            isEnabled = currentTraceIndex > 0
            addActionListener {
                if (currentTraceIndex > 0) {
                    currentTraceIndex--
                    scrollToCurrentTrace()
                    setupNavigationBar()
                }
            }
        }

        val nextButton = JButton(AllIcons.General.ChevronRight).apply {
            toolTipText = "Next"
            isEnabled = currentTraceIndex < (dataFlowTrace!!.size - 1)
            addActionListener {
                if (currentTraceIndex < dataFlowTrace!!.size - 1) {
                    currentTraceIndex++
                    scrollToCurrentTrace()
                    setupNavigationBar()
                }
            }
        }

        navigationPanel.add(prevButton)
        navigationPanel.add(nextButton)

        navigationPanel.add(Box.createHorizontalStrut(50))

        val scrollableLabel = JBScrollPane(dataFlowLabel).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            border = null
            isOpaque = false
            viewport.isOpaque = false
        }
        navigationPanel.add(scrollableLabel)

        add(navigationPanel, BorderLayout.NORTH)
        revalidate()
        repaint()
    }

    /***
     * Moves the mini-editor caret to the current dataflow_trace element and highlights it.
     */
    private fun scrollToCurrentTrace() {
        val editor = editor ?: return
        val markupModel = editor.markupModel

        dataFlowHighlight?.let { previousHighlighter ->
            if (markupModel.allHighlighters.contains(previousHighlighter)) {
                markupModel.removeHighlighter(previousHighlighter)
            }
        }

        val currentElement = dataFlowTrace?.getOrNull(currentTraceIndex) ?: return

        editor.caretModel.moveToOffset(currentElement.startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)

        dataFlowLabel.text =
            "<html><b>${currentElement.name}</b> is a <i>${currentElement.type.name.lowercase()}</i>.</span></html>"

        val attributes = TextAttributes().apply {
            backgroundColor = getDataFlowHighlightColor(currentElement.type)
            effectType = EffectType.SEARCH_MATCH
            effectColor = Color.darkGray
        }

        dataFlowHighlight = editor.markupModel.addRangeHighlighter(
            currentElement.startOffset,
            currentElement.endOffset,
            HighlighterLayer.SELECTION - 1,  // layer priority
            attributes,
            HighlighterTargetArea.EXACT_RANGE
        )
    }

    /***
     * Returns the highlight color for each DataFlowCategory.
     */
    private fun getDataFlowHighlightColor(type: DataFlowCategory): Color {
        return when (type) {
            DataFlowCategory.SOURCE -> JBColor(Color(255, 217, 255), Color(255, 217, 255))
            DataFlowCategory.SINK -> JBColor(Color(255, 210, 192), Color(255, 210, 192))
            DataFlowCategory.PROPAGATOR -> JBColor(Color(137, 208, 255), Color(137, 208, 255))
        }
    }

    /***
     * Annotates known SRMs in the mini-editor.
     ***/
    private fun annotateSRM(fileContent: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val psiFile = ReadAction.compute<PsiFile, Throwable> {
                val psiFileFactory = PsiFileFactory.getInstance(project)
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName("dummy.java")
                psiFileFactory.createFileFromText("dummy.java", fileType, fileContent)
            }

            val expressions = ReadAction.compute<Collection<PsiMethodCallExpression>, Throwable> {
                PsiTreeUtil.findChildrenOfType(psiFile, PsiMethodCallExpression::class.java)
            }

            val highlightTasks = mutableListOf<() -> Unit>()

            for (expr in expressions) {
                val (method, signature) = ReadAction.compute<Pair<PsiMethod?, String>?, Throwable> {
                    val m = expr.resolveMethod() ?: return@compute null
                    m to MethodUtil.getMethodSignature(m)
                } ?: continue

                if (SrmFinder.isSRM(signature)) {
                    val tooltip = PluginBundle.lazy("fixmysast.tooltip.SRM_TOOLTIP_TEMPLATE").get()
                        .format(signature, SrmFinder.getSrmAndCweCategory(signature).joinToString(","))

                    val range = expr.textRange
                    val start = range.startOffset
                    val end = range.endOffset

                    highlightTasks.add {
                        editor?.markupModel?.addRangeHighlighter(
                            start, end,
                            HighlighterLayer.ERROR,
                            TextAttributes(
                                null, Color.lightGray,
                                Color.lightGray, EffectType.SEARCH_MATCH, Font.PLAIN
                            ),
                            HighlighterTargetArea.EXACT_RANGE
                        )?.apply {
                            errorStripeTooltip = tooltip
                            gutterIconRenderer = object : GutterIconRenderer() {
                                override fun getIcon() = IconUtils.getSRMGutterIcon(signature)
                                override fun getTooltipText() =
                                    SrmFinder.getSrmAndCweCategory(signature).joinToString(",")

                                override fun equals(other: Any?) = false
                                override fun hashCode() = icon.hashCode()
                            }
                        }
                    }
                }
            }

            ApplicationManager.getApplication().invokeLater {
                highlightTasks.forEach { it() }
            }
        }
    }
}