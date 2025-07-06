package de.fraunhofer.iem.fixmysast.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.fixmysast.comm.LlmApiNotifier
import de.fraunhofer.iem.fixmysast.llmService.LLMClient
import de.fraunhofer.iem.fixmysast.sast.SASTParsedResult
import de.fraunhofer.iem.fixmysast.sast.dataModel.ExpertiseLevel
import de.fraunhofer.iem.fixmysast.sast.dataModel.SASTIssue
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.yaml.snakeyaml.Yaml
import javax.swing.JPanel

//Helper function for aesthetics
class ExplanationPanel(project: Project) : JPanel() {

    val browser = JBCefBrowser()
    val bus: MessageBus = project.messageBus

    init {
        // Use JCEF browser for rich HTML content

        browser.loadHTML("<i>Click a vulnerability to see explanation</i>")
        add(browser.component)

        //Subscribe to the response topic to get response
        bus.connect().subscribe(LlmApiNotifier.GET_RESPONSE_TOPIC, object : LlmApiNotifier {
            override fun sendRequest(results: SASTParsedResult) {
                TODO("Not yet implemented")
            }

            override fun getResponse(
                results: SASTParsedResult,
                issue: SASTIssue,
                level: ExpertiseLevel
            ) {

                renderExplanation(results, issue, level)
            }
        })
    }


    private fun renderExplanation(parsedResult: SASTParsedResult,
                                  issue: SASTIssue,
                                  level: ExpertiseLevel
    ) {

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

    //Instruct chat respones to produce YAML
    //parse YAML for proper vars
    //wrap YAML recieved elements with HTML code
    private fun getSectionsFromYaml(response: String): Triple<String, String, String> {
        val yaml = Yaml()
        val data = yaml.load<Map<String, Any>>(response)
        val explanationSection = data["Explanation"] as? String ?: error("Explanation missing or not a string")
        val exampleSection = data["Example Code"] as? String ?: " "
        val codeSection = data["CodeFixSuggestion"] as? String ?: error("Code missing or not a string")
        return Triple(explanationSection, exampleSection, codeSection)
    }
    private fun showHtml(markdown: String, issue: SASTIssue, browser: JBCefBrowser){
        ReadAction.nonBlocking<String> {
            val (explanation, exampleCode, fixSuggestion) = getSectionsFromYaml(markdown)

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

        val explStart    = findContentStart(src, "Explanation").takeIf { it != -1 } ?: 0
        val exampleStart = findContentStart(src, "Example code", explStart)
        //val explCodeStart = findContentStart(src,)

        val explEnd    = findContentEnd(src, "Example code").takeIf { it != -1 } ?: 0
        val exampleEnd = findContentEnd(src, "CodeFixSuggestion", explEnd)
        val fixStart     = findContentStart(src, "CodeFixSuggestion",
            if (exampleStart != -1) exampleStart else explStart)

        /* -------- slice the payloads (headers already excluded) -------- */
        val explanation = when {
            explStart == -1 -> ""
            exampleStart != -1 -> src.substring(explStart, explEnd + 1).trim()
            fixStart     != -1 -> src.substring(explStart, exampleEnd + 1).trim()
            else                -> src.substring(explStart).trim()
        }

        val exampleCode = if (exampleStart != -1) {
            if (fixStart != -1) src.substring(exampleStart, exampleEnd + 1).trim()
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

    private lateinit var parsedResult: SASTParsedResult
    // Markdown parser
    fun markdownToHtml(md: String): String {
        val flavour = CommonMarkFlavourDescriptor()
        val ast: ASTNode = MarkdownParser(flavour).buildMarkdownTreeFromString(md)
        return HtmlGenerator(md, ast, flavour).generateHtml()
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
}