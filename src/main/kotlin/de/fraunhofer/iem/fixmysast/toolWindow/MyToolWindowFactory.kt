package de.fraunhofer.iem.fixmysast.toolWindow
import ai.grazie.detector.ngram.main
import ai.grazie.utils.mpp.time.invoke
import com.intellij.codeInsight.inline.completion.suggestion.invoke
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
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.ast.ASTNode
class MyToolWindowFactory : ToolWindowFactory {
    //Helper function for aesthetics

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
    private fun wrapHtmlWithStyle(body: String, headertags: String): String {
        return """
            <html>
            <head>
                <style>
                    body {
                        font-family: 'Segoe UI', sans-serif;
                        font-size: 14pt;
                        line-height: 1.6;
                        padding: 12px;
                        color: #2c2c2c;
                        background-color: #ffffff;
                        }
                    h1, h2, h3 {
                        color: #003366;
                        margin-top: 1em;
                        margin-bottom: 0.5em;
                        }
                    code, pre {
                        font-family: 'Courier New', monospace;
                        background-color: #f4f4f4;
                        padding: 4px 8px;
                        border-radius: 6px;
                        display: block;
                        white-space: pre-wrap;
                        }
                    strong {
                        font-weight: bold;}
                    em {
                        font-style: italic;
                    }
                    ul, ol {
                        padding-left: 20px;
                        margin-bottom: 10px;
                    }
                    </style>
                </head>
                <body>
                    Vulnerability name: $headertags
                    $body
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

        // Load SARIF results from resources
        val parsedResult = SASTParser.parseSarifFromProject(project)

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
                val hasExplanation = parsedResult.llmExplanations.containsKey(value)
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
        // 1) Use cache if we have it
        val cached = explanationCache[issue to level]
        val markdown: String = explanationCache[issue to level] ?: run {
            val fresh = LLMClient.getExplanation(issue,level)
            if (fresh != null) {
                explanationCache[issue to level] = fresh
                fresh
            } else {
                "LLM failed to generate an explanation"
            }
        }

        val rawHtml     = markdownToHtml(markdown)
        val headertag   = issue.tags.firstOrNull() ?: "N/A"
        val wrappedHtml = wrapHtmlWithStyle(rawHtml, headertag)

        SwingUtilities.invokeLater { browser.loadHTML(wrappedHtml) }
    }

}


