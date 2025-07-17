package de.fraunhofer.iem.fixmysast.ui

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBus
import com.intellij.util.ui.JBUI
import de.fraunhofer.iem.fixmysast.comm.ExplanationNotifier
import de.fraunhofer.iem.fixmysast.llm.Explanation
import de.fraunhofer.iem.fixmysast.sast.Issue
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.yaml.snakeyaml.Yaml
import java.awt.BorderLayout
import javax.swing.JPanel

//Helper function for aesthetics
class ExplanationPanel(project: Project) : JPanel() {

    val sastResult = JBLabel()
    val browser = JBCefBrowser()
    val bus: MessageBus = project.messageBus

    init {
        layout = BorderLayout()

        sastResult.setBorder(JBUI.Borders.empty(10))
        sastResult.isAllowAutoWrapping = true
        add(sastResult, BorderLayout.NORTH)

        // Use JCEF browser for rich HTML content
        browser.loadHTML("<i>Click a vulnerability to see explanation</i>")
        add(browser.component, BorderLayout.CENTER)

        //Subscribe to the response topic to get response
        bus.connect().subscribe(
            ExplanationNotifier.SHOW_EXPLANATION_TOPIC,
            object : ExplanationNotifier {

                override fun showExplanation(issue: Issue) {
                    sastResult.text = "<html><b>" + issue.type + "</b> <br>" + issue.message + "</html>"
                    showHtml(issue)
                }
            })
    }

    /*
        Instruct chat respones to produce YAML
        parse YAML for proper vars
        wrap YAML recieved elements with HTML code
     */
    private fun getSectionsFromYaml(response: String?): Explanation {
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
                issue.message,
                issue.severity,
                issue.confidence,
                issue.cwe,
                issue.owasp,
                issue.impact
            )
            temp
        }.finishOnUiThread(ModalityState.any()) { html ->
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
        message: String,
        severity: String?,
        confidence: String?,
        cwe: List<String>?,
        owasp: List<String>?,
        impact: String?
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

        val owaspButtonHtml = owasp?.joinToString(separator = "\n") { tag ->
            """<button class="tag tag-owasp" disabled>${tag}</button>  """
        }

        val tagHtml = """
            <div class="tag-container" style="margin-bottom: 8px;">
            <button class="tag tag-${severity?.lowercase()}">Severity: ${severity}</button>
            <button class="tag tag-${confidence?.lowercase()}">Confidence: ${confidence}</button>
            <button class="tag tag-${impact?.lowercase()}">Impact: ${impact}</button>
            $owaspButtonHtml
            </div>
        """.trimIndent()

        fun simplifyCWE(cweRaw: List<String>): String {
            val cweString = cweRaw[0]
            //removed square brackets
            val trimmed = cweString.trim().removePrefix("[").removeSuffix("]")
            // 2. Extract CWE code (e.g. CWE-79)
            val codeRegex = Regex("""(CWE-\d+)""")
            val codeMatch = codeRegex.find(trimmed)
            val code = codeMatch?.value ?: ""

            // 3. Extract phrase inside parentheses with single quotes: ('...')
            val innerQuoteRegex = Regex("""\('([^']+)'\)""")
            val innerQuoteMatch = innerQuoteRegex.find(trimmed)

            return if (code.isNotEmpty()) {
                if (innerQuoteMatch != null) {
                    // Use the quoted phrase inside parentheses
                    "$code: ${innerQuoteMatch.groupValues[1]}"
                } else {
                    // If no parentheses-quoted phrase, fallback to full description after code
                    val desc = trimmed.substringAfter("$code:").trim()
                    "$code: $desc"
                }
            } else {
                // fallback: return trimmed original string
                trimmed
            }
        }

        val title = simplifyCWE(cwe.orEmpty())

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
.tag {
    display: inline-block;
    font-size: 12px;
    font-weight: 600;
    padding: 2px 8px;
    border-radius: 16px;
    margin-right: 6px;
    margin-top: 4px;
    color: white;
    background-color: #555;
    border: none;
    cursor: default;
    pointer-events: none;
    font-family:"Segoe UI",sans-serif;
    text-align: center;
    user-select:none;
}
.tag-low         { background-color: #4CAF50; color: #000; }
.tag-medium      { background-color: #f9c74f; color: #000; }
.tag-high        { background-color: #d73a49; }
.tag-note        { background-color: #4CAF50; color: #000; }
.tag-warning     { background-color: #f9c74f; color: #000; }
.tag-error       { background-color: #d73a49; }
.tag-critical    { background-color: #6f42c1; }
.tag-owasp       { background-color: #007acc; }
</style>
</head>
<body>
<h1>$title</h1>
$tagHtml
<hr style="border: none; height: 1px; background-color: #003366;">
 
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
}