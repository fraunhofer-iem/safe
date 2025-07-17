package de.fraunhofer.iem.fixmysast.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.fixmysast.PluginBundle
import de.fraunhofer.iem.fixmysast.analysis.SrmFinder
import de.fraunhofer.iem.fixmysast.comm.DataflowNotifier
import de.fraunhofer.iem.fixmysast.comm.ExplanationNotifier
import de.fraunhofer.iem.fixmysast.icons.IconUtils
import de.fraunhofer.iem.fixmysast.icons.PluginIcons
import de.fraunhofer.iem.fixmysast.llm.Explanation
import de.fraunhofer.iem.fixmysast.sast.DataFlowCategory
import de.fraunhofer.iem.fixmysast.sast.DataFlowElement
import de.fraunhofer.iem.fixmysast.sast.Issue
import de.fraunhofer.iem.fixmysast.util.MethodUtil
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.yaml.snakeyaml.Yaml
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

//Helper function for aesthetics
class ExplanationPanel(private val project: Project) : JPanel() {

    val browser = JBCefBrowser()
    val bus: MessageBus = project.messageBus
    private var editor: Editor? = null
    private var dataFlowTrace: List<DataFlowElement>? = null
    private var currentTraceIndex: Int = -1
    private val navigationPanel = JPanel(FlowLayout(FlowLayout.LEFT))
    private val dataFlowPanel = JPanel(BorderLayout()).apply {
        background = JBColor(Color(255, 255, 204), Color(60, 60, 60))
        border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
    }

    private val dataFlowLabel = JLabel().apply {
        foreground = Color.BLACK
        font = font.deriveFont(Font.PLAIN)
    }

    var dataFlowHighlight: RangeHighlighter? = null

    init {
        layout = BorderLayout()

        bus.connect().subscribe(DataflowNotifier.SHOW_EDITOR_TOPIC, object : DataflowNotifier {

            override fun showEditor(issue: Issue) {
                showFileContent(issue)
            }
        })

        //Subscribe to the response topic to get response
        bus.connect().subscribe(ExplanationNotifier.SHOW_EXPLANATION_TOPIC, object : ExplanationNotifier {

            override fun showExplanation(issue: Issue) {
                showHtml(issue)
            }
        })
    }

    /*
        Instruct chat respones to produce YAML
        parse YAML for proper vars
        wrap YAML recieved elements with HTML code
     */
    private fun getSectionsFromYaml(response: String): Explanation {
        val yaml = Yaml()
        val data = yaml.load<Map<String, Any>>(response)
        val explanationSection = data["Explanation"] as? String ?: error("Explanation missing or not a string")
        val exampleSection = data["ExampleCode"] as? String ?: " "
        val exampleCodeExplanation = data["ExampleCodeExplanation"] as? String ?: " "
        val codeSection = data["CodeFixSuggestion"] as? String ?: error("Code missing or not a string")
        val codeSectionExplanation =
            data["CodeFixSuggestionExplanation"] as? String ?: error("Code missing or not a string")
        return Explanation(
            explanationSection,
            exampleSection.trimStart(),
            exampleCodeExplanation,
            codeSection.trimStart(),
            codeSectionExplanation
        )
    }

    private fun showHtml(issue: Issue) {
        removeAll()
        removeEditorIfPresent()

        ReadAction.nonBlocking<String> {
            val (explanation,
                exampleCode,
                exampleCodeExplanation,
                fixSuggestion,
                fixSuggestionExplanation) = getSectionsFromYaml(
                issue.explanation
            )

            //val rawHtml = markdownToHtml(markdown)
            val headerTags = issue.tags.firstOrNull() ?: "N/A"
            val temp = wrapHtmlWithStyle(
                explanation,
                exampleCode,
                exampleCodeExplanation,
                fixSuggestion,
                fixSuggestionExplanation,
                headerTags,
                issue.type,
                issue.message
            )
            temp
        }.finishOnUiThread(ModalityState.any()) { html ->
            browser.loadHTML(html)
        }.submit(AppExecutorUtil.getAppExecutorService())

        add(browser.component, BorderLayout.CENTER)

        revalidate()
        repaint()
    }

    fun splitSections(src: String): Triple<String, String, String> {
        /** Return the index just after a header line that starts a new section. */
        fun findContentStart(text: String, keyword: String, from: Int = 0): Int {
            //   (?im)  → multiline, case‑insensitive
            //   ^\s*   → start of line, optional spaces
            //   keyword\s*:? → the actual header, optional spaces and colon
            val headerRe = Regex("(?im)^\\s*${Regex.escape(keyword)}\\s*:?", RegexOption.MULTILINE)
            val m = headerRe.find(text, from) ?: return -1
            var idx = m.range.last + 1                 // first char right after header
            while (idx < text.length && text[idx].isWhitespace()) idx++   // skip blank line
            return idx
        }

        fun findContentEnd(text: String, keyword: String, from: Int = 0): Int {
            //   (?im)  → multiline, case‑insensitive
            //   ^\s*   → start of line, optional spaces
            //   keyword\s*:? → the actual header, optional spaces and colon
            val headerRe = Regex("(?im)^\\s*${Regex.escape(keyword)}\\s*:?", RegexOption.MULTILINE)
            val m = headerRe.find(text, from) ?: return -1
            var idx = m.range.first - 1                 // first char right after header
            // TODO> below carefuly debug
            while (idx > 0 && text[idx].isWhitespace()) idx--   // skip blank line
            return idx
        }

        val explStart = findContentStart(src, "Explanation").takeIf { it != -1 } ?: 0
        val exampleStart = findContentStart(src, "Example code", explStart)
        //val explCodeStart = findContentStart(src,)

        val explEnd = findContentEnd(src, "Example code").takeIf { it != -1 } ?: 0
        val exampleEnd = findContentEnd(src, "CodeFixSuggestion", explEnd)
        val fixStart = findContentStart(
            src, "CodeFixSuggestion",
            if (exampleStart != -1) exampleStart else explStart
        )

        /* -------- slice the payloads (headers already excluded) -------- */
        val explanation = when {
            explStart == -1 -> ""
            exampleStart != -1 -> src.substring(explStart, explEnd + 1).trim()
            fixStart != -1 -> src.substring(explStart, exampleEnd + 1).trim()
            else -> src.substring(explStart).trim()
        }

        val exampleCode = if (exampleStart != -1) {
            if (fixStart != -1) src.substring(exampleStart, exampleEnd + 1).trim()
            else src.substring(exampleStart).trim()
        } else ""

        val fixSuggestion = if (fixStart != -1) {
            src.substring(fixStart).trim()
        } else ""
        return Triple(explanation, exampleCode, fixSuggestion)
    }

    // Markdown parser
    fun markdownToHtml(md: String): String {
        val flavour = CommonMarkFlavourDescriptor()
        val ast: ASTNode = MarkdownParser(flavour).buildMarkdownTreeFromString(md)
        return HtmlGenerator(md, ast, flavour).generateHtml()
    }


    //CommonMarkFlavourDescriptor flavourDescriptor = new CommonMarkFlavourDescriptor();
//String html = new MarkdownToHtmlConverter(flavourDescriptor).convertMarkdownToHtml(markdownString, null);
    private fun wrapHtmlWithStyle(
        explanation: String,
        exampleCodeRaw: String,
        exampleCodeExplanation: String,
        fixSuggestion: String,
        fixSuggestionExplanation: String,
        headerTags: String,
        type: String,
        message: String
    ): String {

        /* ------------------------------------------------------------------ */
        /* 1. Turn whatever we got for example code into a proper HTML block  */
        /* -------------------------------------------fF----------------------- */

        fun escapeHtml(txt: String) =
            txt.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun sendStringtoHtmlFormat(example: String): String {
            return when {
                example.isBlank() -> ""                 // no section at all

                // already an HTML <pre> block  → use as‑is
                Regex("""<\s*pre""").containsMatchIn(example) ->
                    example

                // contains ``` fences anywhere → run JUST that snippet through markdown converter
                example.contains("```") ->
                    markdownToHtml(exampleCodeRaw)

                // plain raw code               → escape & wrap ourselves
                else ->
                    """<pre><code class="language-java">${
                        escapeHtml(
                            example.trim().trimStart()
                        )
                    }</code></pre>""".trimIndent()
            }
        }

        val exampleHtml = sendStringtoHtmlFormat(exampleCodeRaw).trimStart()
        val fixSuggestion = sendStringtoHtmlFormat(fixSuggestion).trimStart()

        /* ------------------------------------------------------------------ */
        /* 2. Build the final HTML page                                       */
        /* ------------------------------------------------------------------ */

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="color-scheme" content="light"/>
<style>
            body        { background:#fff; color:#1c1c1c;
                          font-family:"Segoe UI",sans-serif;
                          font-size:14px; line-height:1.6; margin:0; padding:16px; }
            h1,h2       { color:#003366; font-weight:600; margin:1.0em 0 .6em; }
            h1          { margin-top:0; font-size:24px; }
            h2          { font-size:18px; }
            pre,code    { display: block; background:#f5f5f5; font-family:"Courier New",monospace;
                          padding:4px 8px; border-radius:6px; }
            pre         { overflow-x:auto; }
            dl          { margin:0 0 1em; }
            dt          { font-weight:600; display:inline; }
            dd          { margin:0 0 .5em .5em; display:inline; }
</style>
</head>
<body>
<h1>$headerTags</h1>
<hr style="border: none; height: 1px; background-color: #003366;">

<h2>Information provided by the SAST</h2>
          <dl>
<dt>Type:</dt><dd>$type</dd> <br />
<dt>Description:</dt><dd>$message</dd>
</dl>
 
          <section>
<h2>Explanation</h2>
            $explanation
</section>
 
          ${if (exampleHtml.isNotBlank()) """
<section>
<h2>Example&nbsp;Code</h2>
              $exampleCodeExplanation
              $exampleHtml
</section>""" else ""}
 
          ${if (fixSuggestion.isNotBlank()) """
<section>
<h2>Code&nbsp;Fix&nbsp;Suggestion</h2>
$fixSuggestionExplanation
$fixSuggestion
</section>""" else ""}
</body>
</html>
    """.trimIndent()
    }

    fun showFileContent(issue: Issue) {
        removeAll()
        removeEditorIfPresent()

        val relativePath = issue.path ?: return
        val absolutePath = "${project.basePath}/$relativePath"
        val fileContent = java.io.File(absolutePath ?: "").takeIf { it.exists() }?.readText() ?: "File not found."
        dataFlowTrace = issue.dataFlowTrace

        val factory = EditorFactory.getInstance()
        val document: Document = factory.createDocument(fileContent)

        editor = EditorFactory.getInstance().createEditor(document, project).apply{
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isRightMarginShown = true
            settings.additionalLinesCount = 2
        }

        add(editor!!.component, BorderLayout.CENTER)

        if (!dataFlowTrace.isNullOrEmpty()){
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

        navigationPanel.add(Box.createHorizontalStrut(20))
        navigationPanel.add(dataFlowLabel)

        add(navigationPanel, BorderLayout.NORTH)
        revalidate()
        repaint()
    }

    /***
     * Moves the mini-editor caret to the current dataflow_trace element and highlights it.
     */
    private fun scrollToCurrentTrace() {
        dataFlowHighlight?.let { editor?.markupModel?.removeHighlighter(it) }
        val currentElement = dataFlowTrace?.getOrNull(currentTraceIndex) ?: return
        val editor = editor ?: return

        editor.caretModel.moveToOffset(currentElement.startOffset)
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)

        dataFlowLabel.text = "<html><span style='color:white;'><b>${currentElement.name}</b> is a <i>${currentElement.type.name.lowercase()}</i>.</span></html>"

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
            DataFlowCategory.SOURCE -> Color(118, 10, 174)
            DataFlowCategory.SINK -> Color(237, 64, 64)
            DataFlowCategory.PROPAGATOR -> Color(102, 145, 16)
        }
    }

    /***
     * Annotates the dataflow_trace (if present) in the mini-editor.
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

            highlighter.errorStripeTooltip = dataFlowElement.name + " is a " + dataFlowElement.type.toString().lowercase() + "."

            val icon: Icon = PluginIcons.SOURCE // Place-holder TODO Change to SRM-specific icon

            highlighter.gutterIconRenderer = object : GutterIconRenderer() {
                override fun getIcon(): Icon = icon
                override fun getTooltipText(): String? = tooltipText
                override fun equals(p0: Any?): Boolean = false
                override fun hashCode(): Int = icon.hashCode()
            }
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

            val expressions = ReadAction.compute<Collection<com.intellij.psi.PsiMethodCallExpression>, Throwable> {
                PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiMethodCallExpression::class.java)
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
                            TextAttributes(null, Color(60, 47, 47),
                                Color.darkGray, EffectType.SEARCH_MATCH, Font.PLAIN),
                            HighlighterTargetArea.EXACT_RANGE
                        )?.apply {
                            errorStripeTooltip = tooltip
                            gutterIconRenderer = object : GutterIconRenderer() {
                                override fun getIcon() = IconUtils.getSRMGutterIcon(signature)
                                override fun getTooltipText() = SrmFinder.getSrmAndCweCategory(signature).joinToString(",")
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

