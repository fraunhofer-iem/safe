package de.fraunhofer.iem.fixmysast.toolWindow
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser

import de.fraunhofer.iem.fixmysast.sast.SASTIssue
import de.fraunhofer.iem.fixmysast.sast.SASTParser
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import de.fraunhofer.iem.fixmysast.sast.ExpertiseLevel
import com.intellij.openapi.ui.ComboBox
import de.fraunhofer.iem.fixmysast.sast.LLMClient
import io.ktor.client.request.invoke
import io.ktor.http.invoke

import com.intellij.openapi.util.Disposer
import de.fraunhofer.iem.fixmysast.sast.LevelStateService
import de.fraunhofer.iem.fixmysast.sast.SASTParsedResult
import kotlinx.serialization.decodeFromString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.ast.ASTNode
import org.yaml.snakeyaml.Yaml

class MyToolWindowFactory : ToolWindowFactory {
    //Helper function for aesthetics

    private lateinit var parsedResult: SASTParsedResult
    // Markdown parser
    fun markdownToHtml(md: String): String {
        val flavour = CommonMarkFlavourDescriptor()
        val ast: ASTNode = MarkdownParser(flavour).buildMarkdownTreeFromString(md)
        return HtmlGenerator(md,ast,flavour).generateHtml()
    }
    //val explanationCache = mutableMapOf<SASTIssue, String>()
    private val explanationCache : MutableMap<Pair<SASTIssue, ExpertiseLevel>, String> = mutableMapOf()
    //<Pair<SASTIssue, ExpertiseLevel>, String>()

    //CommonMarkFlavourDescriptor flavourDescriptor = new CommonMarkFlavourDescriptor();
    //String html = new MarkdownToHtmlConverter(flavourDescriptor).convertMarkdownToHtml(markdownString, null);
    private fun wrapHtmlWithStyle(
        explanation: String,
        exampleCodeRaw: String,
        fixSuggestion: String,
        headerTags: String,
        type: String,
        message: String
    ): String {

        /* ------------------------------------------------------------------ */
        /* 1. Turn whatever we got for example code into a proper HTML block  */
        /* -------------------------------------------fF----------------------- */

        fun escapeHtml(txt: String) =
            txt.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun sendStringtoHtmlFormat(example:String): String {
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
                    "<pre><code>${escapeHtml(example.trim())}</code></pre>"
            }
        }

        val exampleHtml = sendStringtoHtmlFormat(exampleCodeRaw)
        val fixSuggestion = sendStringtoHtmlFormat(fixSuggestion)

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
            pre,code    { background:#f5f5f5; font-family:"Courier New",monospace;
                          padding:4px 8px; border-radius:6px; }
            pre         { overflow-x:auto; }
            dl          { margin:0 0 1em; }
            dt          { font-weight:600; display:inline; }
            dd          { margin:0 0 .5em .5em; display:inline; }
</style>
</head>
<body>
<h1>$headerTags</h1>
 
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
              $exampleHtml
</section>""" else ""}
 
          ${if (fixSuggestion.isNotBlank()) """
<section>
<h2>Code&nbsp;Fix&nbsp;Suggestion</h2>
              $fixSuggestion
</section>""" else ""}
</body>
</html>
    """.trimIndent()
    }




    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        val leftPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = TitledBorder("Vulnerabilities")
        }

        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = TitledBorder("Description")
        }

        // Use JCEF browser for rich HTML content
        val browser = JBCefBrowser()

        browser.loadHTML("<i>Click a vulnerability to see explanation</i>")
        rightPanel.add(browser.component)

//        val levelSelector = ComboBox(ExpertiseLevel.values())
//        rightPanel.add(levelSelector)

        // Load SARIF results from resources
        parsedResult = SASTParser.parseSarifFromProject(project)


        var list = mutableListOf<SASTIssue>()

        parsedResult.groupedIssues.forEach { (type, issues) ->
            list.addAll(issues.map { it })
        }

        var issuesList = JBList<SASTIssue>(list)
        //Aesthetic Layout
        issuesList.cellRenderer = (object : ListCellRenderer<SASTIssue> {
            override fun getListCellRendererComponent(
                list: JList<out SASTIssue>,
                value: SASTIssue,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val panel = JPanel(BorderLayout(5,0)) //space btwn indicator & label
                val label = JLabel(value.message)

                //Green if explanation is ready, red otherwise
                val lvl = LevelStateService.get().current
                val hasExplanation = parsedResult.llmExplanations.containsKey(value to lvl)
                val color = if (hasExplanation) Color(0,128,0) else Color(200,0,0)

                val indicator = object: JComponent() {
                    override fun paintComponent(g: Graphics) {
                        super.paintComponent(g)
                        g.color = color
                        g.fillOval(0, 0, 10, 10)
                    }

                    override fun getPreferredSize(): Dimension = Dimension(10, 10)
                }

                panel.add(indicator, BorderLayout.WEST)
                panel.add(label, BorderLayout.CENTER)

                //styling for slection
                if (isSelected) {
                    panel.background = list.selectionBackground
                    label.foreground = list.selectionForeground

                } else {
                    panel.background = list.background
                    label.foreground = list.foreground
                }
                return panel
            }
        }) as ListCellRenderer<in SASTIssue>

        issuesList.addListSelectionListener { e: ListSelectionEvent ->

            if (!e.valueIsAdjusting) {
                println(issuesList.selectedValue)

                val issue = issuesList.selectedValue ?: return@addListSelectionListener
                //val level = levelSelector.selectedItem as ExpertiseLevel
                val level = de.fraunhofer.iem.fixmysast.sast.LevelStateService.get().current
                renderExplanation(issue,level,browser)

            }
        }

        val listScrollPane = JBScrollPane(issuesList)
        leftPanel.add(listScrollPane)

        val leftScrollPane = JBScrollPane(leftPanel)
        val rightScrollPane = JBScrollPane(rightPanel)


        val splitPane = JBSplitter(false, 0.2f).apply {
            firstComponent = leftScrollPane
            secondComponent = rightScrollPane
        }

        val mainPanel = JPanel().apply {

        }

        val levelListener: (ExpertiseLevel) -> Unit = listener@ {newLevel ->
            val issue = issuesList.selectedValue ?: return@listener
            renderExplanation(issue, newLevel, browser)
        }

        LevelStateService.get().addListener(levelListener)

        Disposer.register(toolWindow.disposable) {
            LevelStateService.get().removeListener(levelListener)
        }

        val content = ContentFactory.getInstance().createContent(splitPane, "", false)
        toolWindow.contentManager.addContent(content)
    }
    private fun renderExplanation(issue: SASTIssue,
                                  level: ExpertiseLevel,
                                  browser: JBCefBrowser) {

        fun cacheKey() = issue to level

        //Try parsed SARIF Explanations
        val fromParser = parsedResult.llmExplanations[cacheKey()]
        if (fromParser != null) {
            showHtml(fromParser,issue,browser)
            return
        }
        //2 look in this-session cache
        val fromMemory = explanationCache[cacheKey()]
        if (fromMemory != null)
        {
            showHtml(fromMemory,issue,browser)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val fresh = LLMClient.getExplanation(issue,level)
                ?: "LLM failed to generate an explanation"

            explanationCache[cacheKey()] = fresh
            parsedResult.llmExplanations[cacheKey()] = fresh

            showHtml(fresh, issue, browser)
        }

    }
    private fun showHtml(markdown: String, issue: SASTIssue, browser: JBCefBrowser){
        ReadAction.nonBlocking<String> {
            val (explanation, exampleCode, fixSuggestion) = splitSections(markdown)
            val manualMarkdown = formatSectionsToMarkdown(explanation, exampleCode, fixSuggestion)
            //val rawHtml = markdownToHtml(markdown)
            val headerTags = issue.tags.firstOrNull() ?: "N/A"
            wrapHtmlWithStyle(explanation, exampleCode, fixSuggestion, headerTags, issue.type, issue.message,)
        }.finishOnUiThread(ModalityState.any()){ html ->
            browser.loadHTML(html)
        }.submit(AppExecutorUtil.getAppExecutorService())
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

        val explStart    = findContentStart(src, "Explanation").takeIf { it != -1 } ?: 0
        val exampleStart = findContentStart(src, "Example code", explStart)
        val fixStart     = findContentStart(src, "CodeFixSuggestion",
            if (exampleStart != -1) exampleStart else explStart)

        /* -------- slice the payloads (headers already excluded) -------- */
        val explanation = when {
            explStart == -1 -> ""
            exampleStart != -1 -> src.substring(explStart, exampleStart-15).trim()
            fixStart     != -1 -> src.substring(explStart, fixStart-20).trim()
            else                -> src.substring(explStart).trim()
        }

        val exampleCode = if (exampleStart != -1) {
            if (fixStart != -1) src.substring(exampleStart, fixStart-20).trim()
            else                src.substring(exampleStart).trim()
        } else ""

        val fixSuggestion = if (fixStart != -1) {
            src.substring(fixStart).trim()
        } else ""
        println(explanation)
        println(exampleCode)
        println(fixSuggestion)
        return Triple(explanation, exampleCode, fixSuggestion)
    }

    fun formatSectionsToMarkdown(
        explanation: String,
        exampleCode: String,
        fixSuggestion: String
    ): String {
        val sb = StringBuilder()

        if (explanation.isNotBlank()) {

            sb.append(explanation.trim()).append("\n\n")
        }

        if (exampleCode.isNotBlank()) {
            // Put code snippet inside triple backticks for code block formatting
            sb.append("```java\n")  // adjust language if not Java
            sb.append(exampleCode.trim()).append("\n")
            sb.append("```\n\n")
        }

        if (fixSuggestion.isNotBlank()) {
            // Put code snippet inside triple backticks for code block formatting
            sb.append("```java\n")  // adjust language if not Java
            sb.append(fixSuggestion.trim()).append("\n")
            sb.append("```\n\n")
        }

        return sb.toString()
    }

}


