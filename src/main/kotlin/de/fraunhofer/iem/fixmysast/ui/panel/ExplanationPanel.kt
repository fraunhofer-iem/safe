package de.fraunhofer.iem.fixmysast.ui.panel

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.fixmysast.comm.EXPERTISE_LEVEL_CHANGE_TOPIC
import de.fraunhofer.iem.fixmysast.comm.ExplanationNotifier
import de.fraunhofer.iem.fixmysast.llm.Explanation
import de.fraunhofer.iem.fixmysast.llm.LlmClient
import de.fraunhofer.iem.fixmysast.sast.CweMitigationSummary
import de.fraunhofer.iem.fixmysast.sast.Issue
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.yaml.snakeyaml.Yaml
import java.awt.BorderLayout
import javax.swing.JPanel
import de.fraunhofer.iem.fixmysast.comm.ExpertiseLevelChangeNotifier

//Helper function for aesthetics
class ExplanationPanel(private val project: Project) : JPanel() {


    val browser = JBCefBrowser()
    val bus: MessageBus = project.messageBus
    private lateinit var currentIssue: Issue
    private val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    val props = PropertiesComponent.getInstance(project)

    init {
        layout = BorderLayout()

        // Use JCEF browser for rich HTML content
        browser.loadHTML("<i>Click a vulnerability to see explanation</i>")
        add(browser.component, BorderLayout.CENTER)


        Disposer.register(browser, jsQuery)

        // Handle thumbs up/down feedback
        jsQuery.addHandler { feedback ->
            val issue = currentIssue
            if(issue == null)
            {
                println("Issue is null :)")
            }
            if (feedback == "bad" && issue != null) {

                ApplicationManager.getApplication().executeOnPooledThread {
                    Notifications.Bus.notify(
                        Notification(
                            "Notification",
                            "FixMySAST Update",
                            "Re-requesting a better explanation. Please wait.",
                            NotificationType.INFORMATION
                        )
                    )

                    val oldResp = issue.explanation
                    val newResp = LlmClient.updateExplanation(issue, project)

                    ApplicationManager.getApplication().invokeLater {
                        try {
                            getSectionsFromYaml(newResp) // To verify the response is in correct format
                            issue.explanation = newResp
                            Notifications.Bus.notify(
                                Notification(
                                    "Notification",
                                    "FixMySAST Update",
                                    "Successfully re-generated new response!",
                                    NotificationType.INFORMATION
                                )
                            )
                        } catch (e: Exception) {
                            issue.explanation = oldResp
                            Notifications.Bus.notify(
                                Notification(
                                    "Notification",
                                    "FixMySAST Update",
                                    "Failed to re-generate new response. Please try after sometime",
                                    NotificationType.WARNING
                                )
                            )
                        }

                        if (issue.explanation.equals("N/A")) {
                            browser.loadHTML("<i>LLM is still running please wait.</i>")
                        } else {
                            showHtml(issue, jsQuery)
                        }
                    }
                }
            }
            null
        }

        //Subscribe to the response topic to get response
        bus.connect().subscribe(
            ExplanationNotifier.SHOW_EXPLANATION_TOPIC,
            object : ExplanationNotifier {

                override fun showExplanation(issue: Issue) {
                    currentIssue = issue

                    if (issue.explanation.equals("N/A")) {
                        browser.loadHTML("<i>LLM is still running please wait.</i>")
                    } else {
                        showHtml(issue, jsQuery)
                    }
                }
            })

        project.messageBus.connect().subscribe(
            EXPERTISE_LEVEL_CHANGE_TOPIC,
            object : ExpertiseLevelChangeNotifier {
                        override fun onExpertiseLevelChanged(project: Project) {
                            println("Level change detected - refresshing explanation")

                            currentIssue?.let { issue ->

                                issue.explanation = LlmClient.getExplanation(issue, project)
                                showHtml(issue, jsQuery)

                            }
                }
            }
        )
    }
    /*
        Instruct chat respones to produce YAML
        parse YAML for proper vars
        wrap YAML recieved elements with HTML code
     */
    private fun getSectionsFromYaml(response: String?): Explanation {
        val yaml = Yaml()
        val data = yaml.load<Map<String, Any>>(response)
        val overviewSection = data["overview"] as? String ?: error("Explanation missing or not a string")
        val explanationSection = data["explanation"] as? String ?: error("Explanation missing or not a string")
        val exampleSection = data["example"] as? String ?: " "
        val exampleCodeExplanation = data["exampleDescription"] as? String ?: " "

        return Explanation(
            overviewSection,
            explanationSection,
            exampleSection.trimStart(),
            exampleCodeExplanation
        )
    }

    private fun showHtml(issue: Issue, jsQuery: JBCefJSQuery) {
        println("PRINT INSIDE SHOW HTML")

        println(issue.explanation)

        ReadAction.nonBlocking<String> {
            val (overview,
                explanation,
                exampleCode,
                exampleCodeExplanation) = getSectionsFromYaml(
                issue.explanation
            )

            //val rawHtml = markdownToHtml(markdown)
            val headerTags = issue.tags.firstOrNull() ?: "N/A"
            val temp = wrapHtmlWithStyle(
                overview,
                explanation,
                exampleCode,
                exampleCodeExplanation,
                headerTags,
                issue.type,
                issue.message,
                issue.severity,
                issue.confidence,
                issue.cwe,
                issue.owasp,
                issue.impact,
                issue.location.codeSnippet,
                jsQuery
            )
            temp
        }.finishOnUiThread(ModalityState.any()) { html ->

            val htmlWithBridge = html.replace(
                "</body>,",
                """
                <script>
                window.feedbackBridge = function(feedback) {
                ${jsQuery.inject("feedback")}
                }
                </script>
                </body>
            """.trimIndent()
            )
            println("Inject string: ${jsQuery.inject("feedback")}")
            browser.loadHTML(htmlWithBridge)
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

        val explStart = findContentStart(src, "overview").takeIf { it != -1 } ?: 0
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
        overview: String,
        explanation: String,
        exampleCodeRaw: String,
        exampleCodeExplanation: String,
        headerTags: String,
        type: String,
        message: String,
        severity: String?,
        confidence: String?,
        cwe: List<String>?,
        owasp: List<String>?,
        impact: String?,
        codeSnippet: String?,
        jsQuery: JBCefJSQuery
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
        val originalCodeSnippet = codeSnippet?.let { sendStringtoHtmlFormat(it).trimStart() }

        //Formatting for multiple OWASP tags
        val owaspButtonHtml = owasp?.joinToString(separator = "\n") { tag ->
            """<button class="tag tag-owasp" disabled>${tag}</button>  """
        }
        //Formatting for all buttons on screen
        val tagHtml = """
            <div class="tag-container" style="margin-bottom: 8px;">
            <button class="tag tag-${severity?.lowercase()}">Severity: ${severity}</button>
            <button class="tag tag-confidence-${confidence?.lowercase()}">Confidence: ${confidence}</button>
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

        fun getCweIdDigitOnly(): String {
            return cwe?.takeIf { it.isNotEmpty() }
                ?.firstOrNull()
                ?.split(":")
                ?.firstOrNull()
                ?.split("-")
                ?.takeIf { it.size == 2 }
                ?.getOrNull(1) ?: ""
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
            h1,h2, h3       { color:#003366; font-weight:600; margin:1.0em 0 .6em; }
            h1          { margin-top:0; font-size:24px; }
            h2          { font-size:18px; }
            h3          { font-size:16px; }
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
.tag-confidence-low         { background-color: #d73a49; }
.tag-confidence-medium      { background-color: #f9c74f; color: #000; }
.tag-confidence-high        { background-color: #4CAF50; color: #000; }
.tag-note        { background-color: #4CAF50; color: #000; }
.tag-warning     { background-color: #f9c74f; color: #000; }
.tag-error       { background-color: #d73a49; }
.tag-critical    { background-color: #6f42c1; }
.tag-owasp       { background-color: #007acc; }
.feedback-btn {
    padding: 6px 12px;
    margin: 4px;
    border: none;
    border-radius: 6px;
    background-color: #003366;
    color: white;
    font-size: 14 px;
    cursor: pointer;
    }
.feedback-btn:hover {
    background-color: #0055aa
    }
</style>
</head>
<body>
<h1>$title</h1>
$tagHtml
<hr style="border: none; height: 1px; background-color: #003366;">
 
<section>

$overview
The SAST tool flagged the following line of code as the cause of the vulnerability: $originalCodeSnippet
</section>

<section>
$explanation
</section>
 
          ${if (exampleHtml.isNotBlank()) """
<section style="margin-top: 20px;">
<details>
<summary><h3 style="display: inline;">Demonstrative&nbsp;Example</h3></summary>
              $exampleCodeExplanation
              $exampleHtml
</details>
</section>""" else ""}
 
<section style="margin-top: 20px;">
<details>
<summary><h3 style="display: inline;">Potential&nbsp;Mitigation&nbsp;Strategies</h3></summary>
${CweMitigationSummary.getMitigationSummaryFor(getCweIdDigitOnly())}
</details>
</section>

<section id = feedback-section" style="margin-top: 20px;">
<h3>Was this explanation helpful?</h3>
<button class="feedback-btn" onclick="window.feedbackBridge('good')">👍 Yes</button>
<button class="feedback-btn" onclick="window.feedbackBridge('bad')">👎 No</button>
</section>

<script>
window.feedbackBridge = function (feedback) {
${jsQuery.inject("feedback")}
}
</script>
</body>
</html>
    """.trimIndent()
    }
}