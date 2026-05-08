package de.fraunhofer.iem.safe.ui.panel

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
import com.intellij.util.ui.StatusText
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBus
import de.fraunhofer.iem.safe.comm.ExpertiseLevelNotifier
import de.fraunhofer.iem.safe.comm.ExplanationNotifier
import de.fraunhofer.iem.safe.llm.Explanation
import de.fraunhofer.iem.safe.llm.LlmClient
import de.fraunhofer.iem.safe.sast.CweMitigationSummary
import de.fraunhofer.iem.safe.sast.Issue
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
//Helper function for aesthetics
class ExplanationPanel(private val project: Project) : javax.swing.JPanel() {


    val browser = JBCefBrowser()
    val bus: MessageBus = project.messageBus
    private lateinit var currentIssue: Issue
    private val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    val props = PropertiesComponent.getInstance(project)

    /**
     * Tracks whether the JCEF browser currently has anything worth showing —
     * either the loading placeholder or a rendered explanation. When false
     * (no finding selected, or the selected finding has no explanation yet),
     * we hide the browser so the panel's empty-text overlay paints through
     * with the hint that nudges the user toward the right-click menu.
     */
    private var hasBrowserContent: Boolean = false

    /**
     * Standard IntelliJ empty-text overlay. We can't use [com.intellij.ui.components.JBPanelWithEmptyText]
     * directly because in 2025.3 its built-in `StatusText.isStatusVisible()`
     * checks `getComponentCount() == 0` and offers no override hook — the
     * permanent JCEF browser child would always suppress it. Owning our own
     * StatusText and routing visibility through [hasBrowserContent] sidesteps
     * that, while still painting via the same StatusText machinery.
     */
    private val emptyText: StatusText = object : StatusText(this) {
        override fun isStatusVisible(): Boolean = !hasBrowserContent
    }

    init {
        layout = BorderLayout()

        emptyText.text = "Click on a finding and select \"Get Explanation\" to generate one."

        add(browser.component, BorderLayout.CENTER)
        // Start hidden so the panel's empty text is visible until the user asks
        // for an explanation. Replaces the previous `browser.loadHTML(\"…click a
        // vulnerability…\")` placeholder, which the user wanted out of the JCEF
        // instance and into the standard empty-text overlay.
        browser.component.isVisible = false


        Disposer.register(browser, jsQuery)

        // Handle thumbs up/down feedback
        jsQuery.addHandler { feedback ->
            val issue = currentIssue
            if (issue == null) {
                println("Issue is null :)")
            }
            if (feedback == "bad" && issue != null) {

                ApplicationManager.getApplication().executeOnPooledThread {
                    Notifications.Bus.notify(
                        Notification(
                            "SAFE-Blue",
                            "SAFE-Blue Update",
                            "Re-requesting a better explanation. Please wait.",
                            NotificationType.INFORMATION
                        )
                    )

                    val oldResp = issue.explanation
                    val newResp = LlmClient.updateExplanation(issue, project)

                    ApplicationManager.getApplication().invokeLater {
                        try {
                            parseSections(newResp) // To verify the response is in correct format
                            issue.explanation = newResp
                            Notifications.Bus.notify(
                                Notification(
                                    "SAFE-Blue",
                                    "SAFE-Blue Update",
                                    "Successfully re-generated new response!",
                                    NotificationType.INFORMATION
                                )
                            )
                        } catch (e: Exception) {
                            issue.explanation = oldResp
                            Notifications.Bus.notify(
                                Notification(
                                    "SAFE-Blue",
                                    "SAFE-Blue Update",
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
                    val explanation = issue.explanation
                    when {
                        explanation.isNullOrBlank() -> {
                            // No in-memory answer yet — try the persistent cache.
                            // The user may have explained this finding in a previous
                            // session; if so, render it straight away instead of
                            // making them invoke "Get Explanation" again.
                            val level = props.getValue("de.fraunhofer.iem.safe.expertiseValue")
                                ?: "Intermediate"
                            val cached = LlmClient.getCachedExplanation(issue, project, level)
                            if (cached != null) {
                                issue.explanation = cached
                                showHtml(issue, jsQuery)
                                showBrowser()
                            } else {
                                showEmptyHint()
                            }
                        }
                        explanation == "N/A" -> {
                            browser.loadHTML("<i>LLM is still running please wait.</i>")
                            showBrowser()
                        }
                        else -> {
                            showHtml(issue, jsQuery)
                            showBrowser()
                        }
                    }
                }
            })

        project.messageBus.connect().subscribe(
            ExpertiseLevelNotifier.CHANGE_LEVEL_TOPIC,
            object : ExpertiseLevelNotifier {

                override fun changeLevel(level: String) {
                    val issue = currentIssue ?: return

                    // Mark the issue in-flight and render the loading placeholder
                    // in the browser while the regenerated request is on the wire.
                    issue.explanation = "N/A"
                    browser.loadHTML("<i>LLM is still running please wait.</i>")
                    showBrowser()

                    com.intellij.openapi.progress.ProgressManager.getInstance().run(
                        object : com.intellij.openapi.progress.Task.Backgroundable(
                            project,
                            "Regenerating SAFE explanation for ${issue.type}…",
                            true,
                        ) {
                            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                                indicator.isIndeterminate = true
                                indicator.text = "Calling LLM (level: $level)…"
                                val response = LlmClient.sendRequest(issue, project, level)
                                issue.explanation = response ?: "N/A"
                                ApplicationManager.getApplication().invokeLater {
                                    showHtml(issue, jsQuery)
                                    showBrowser()
                                }
                            }

                            override fun onThrowable(error: Throwable) {
                                issue.explanation =
                                    "Error: ${error.message ?: error.javaClass.simpleName}"
                                ApplicationManager.getApplication().invokeLater {
                                    showHtml(issue, jsQuery)
                                    showBrowser()
                                }
                            }
                        }
                    )
                }
            }
        )
    }

    /**
     * StatusText doesn't repaint itself — the host component has to invite it
     * during its own paint pass. We delegate to the StatusText after the panel's
     * default background fill so the hint sits inside the panel's bounds with
     * the standard IntelliJ rendering (font + colors that follow the theme).
     */
    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        emptyText.paint(this, g)
    }

    /**
     * Reveal the JCEF browser. Called whenever the panel is about to render a
     * loading placeholder, an error, or an actual explanation — anything that
     * lives in the browser. Flips the empty-text overlay off so the hint stops
     * painting underneath.
     */
    private fun showBrowser() {
        if (!hasBrowserContent || !browser.component.isVisible) {
            hasBrowserContent = true
            browser.component.isVisible = true
            revalidate()
            repaint()
        }
    }

    /**
     * Hide the JCEF browser so the panel's empty text shines through. Used when
     * the user lands on a finding that has not yet been explained.
     */
    private fun showEmptyHint() {
        if (hasBrowserContent || browser.component.isVisible) {
            hasBrowserContent = false
            browser.component.isVisible = false
            revalidate()
            repaint()
        }
    }

    /**
     * Parses a marker-delimited LLM response into the four sections the explanation
     * UI expects. Mirrors the parser style used by the new SAFE plugin: a regex
     * picks each `**MARKER**:` token and the section body is everything up to the
     * next marker (any `**…**`).
     *
     * Robust against:
     *  - null/blank `response` (LLM call failed or hasn't completed yet),
     *  - prose that omits some markers,
     *  - prose that contains no markers at all (we surface the raw text so the
     *    user at least sees what the model produced).
     *
     * The two `placeholderExplanation` returns also act as the verification gate
     * the previous YAML parser used to throw at — call sites can compare the
     * returned overview to the placeholder strings if they need to detect a
     * malformed response.
     */
    private fun parseSections(response: String?): Explanation {
        val text = response?.trim().orEmpty()
        if (text.isEmpty()) return placeholderExplanation("Explanation is not available yet.")

        val markerKey = Regex(
            """\*\*(OVERVIEW|EXPLANATION|EXAMPLE|EXAMPLE_DESCRIPTION)\*\*\s*:?\s*""",
            RegexOption.IGNORE_CASE,
        )
        val anyMarker = Regex("""\*\*[A-Za-z_]+\*\*""")

        val sections = mutableMapOf<String, String>()
        for (m in markerKey.findAll(text)) {
            val key = m.groupValues[1].uppercase()
            val start = m.range.last + 1
            val end = anyMarker.find(text, start)?.range?.first ?: text.length
            sections[key] = text.substring(start, end).trim()
        }

        val overview = sections["OVERVIEW"].orEmpty()
        val explanation = sections["EXPLANATION"].orEmpty()
        val example = sections["EXAMPLE"] ?: " "
        val exampleDescription = sections["EXAMPLE_DESCRIPTION"] ?: " "

        if (overview.isBlank() && explanation.isBlank()) {
            return placeholderExplanation(
                "The explanation response had no recognised sections. Raw text:\n\n$text"
            )
        }

        return Explanation(
            overview,
            explanation,
            example.trimStart(),
            exampleDescription,
        )
    }

    private fun placeholderExplanation(message: String): Explanation =
        Explanation(message, "", " ", " ")

    private fun showHtml(issue: Issue, jsQuery: JBCefJSQuery) {

        ReadAction.nonBlocking<String> {
            val (overview,
                explanation,
                exampleCode,
                exampleCodeExplanation) = parseSections(
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
        // Some SAST tools (e.g. SARIF reports without Semgrep-specific extensions)
        // don't carry confidence or impact, so render "Unknown" instead of
        // letting Kotlin string templates print the literal "null".
        val severityLabel = severity?.takeIf { it.isNotBlank() } ?: "Unknown"
        val confidenceLabel = confidence?.takeIf { it.isNotBlank() } ?: "Unknown"
        val impactLabel = impact?.takeIf { it.isNotBlank() } ?: "Unknown"

        //Formatting for all buttons on screen
        val tagHtml = """
            <div class="tag-container" style="margin-bottom: 8px;">
            <button class="tag tag-${severityLabel.lowercase()}">Severity: ${severityLabel}</button>
            <button class="tag tag-confidence-${confidenceLabel.lowercase()}">Confidence: ${confidenceLabel}</button>
            <button class="tag tag-${impactLabel.lowercase()}">Impact: ${impactLabel}</button>
            ${owaspButtonHtml.orEmpty()}
            </div>
        """.trimIndent()

        fun simplifyCWE(cweRaw: List<String>): String {
            // Some Semgrep rules don't carry a CWE tag at all (e.g. the
            // `tainted-file-path` rule). Treat the missing case as empty rather
            // than crashing the whole HTML render.
            val cweString = cweRaw.firstOrNull() ?: return ""
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

        // Fall back to the rule id when the finding has no CWE tag (e.g. some
        // Semgrep rules) — empty headings just leave a thick blank stripe at
        // the top of the explanation pane.
        val title = simplifyCWE(cwe.orEmpty()).ifBlank { type }

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
The SAST tool flagged the following line of code as the cause of the vulnerability: $originalCodeSnippet

</section>

<section>
$overview
$explanation
</section>
 
          ${
            if (exampleHtml.isNotBlank()) """
<section style="margin-top: 20px;">
<details>
<summary><h3 style="display: inline;">Demonstrative&nbsp;Example</h3></summary>
              $exampleCodeExplanation
              $exampleHtml
</details>
</section>""" else ""
        }
 
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