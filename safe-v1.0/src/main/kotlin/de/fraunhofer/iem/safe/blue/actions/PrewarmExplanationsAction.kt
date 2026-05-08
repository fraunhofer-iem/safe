package de.fraunhofer.iem.safe.blue.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import de.fraunhofer.iem.safe.blue.ExplanationToolWindow
import de.fraunhofer.iem.safe.blue.llm.LlmClient
import de.fraunhofer.iem.safe.blue.sast.Issue
import de.fraunhofer.iem.safe.blue.study.StudyModeSettings
import de.fraunhofer.iem.safe.blue.study.TelemetryRecorder

/**
 * Toolbar / menu action that walks every imported finding and asks the LLM
 * for an explanation at the *Intermediate* expertise level — the only level
 * the user-study uses for v1. Successful responses are written to the
 * persistent cache by [LlmClient.sendRequest] itself, so participants see
 * cached explanations immediately when they trigger *Get Explanation* on a
 * finding during the study task.
 *
 * Visible only when [StudyModeSettings.enabled] is true. Failures of
 * individual findings are swallowed so one bad call doesn't abort the run.
 */
class PrewarmExplanationsAction : AnAction(
    "Pre-warm Explanations",
    "Generate Intermediate-level explanations for every imported finding (study mode)",
    AllIcons.Actions.Compile,
) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = StudyModeSettings.getInstance().enabled
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tree = ExplanationToolWindow.resultsTree ?: return
        val findings = tree.allIssuesOrEmpty()
        if (findings.isEmpty()) return

        val recorder = TelemetryRecorder.getInstance(project)
        recorder.record("prewarm.started", data = mapOf("count" to findings.size))

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Pre-warming SAFE explanations…", true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                var ok = 0
                var failed = 0

                for ((idx, issue) in findings.withIndex()) {
                    if (indicator.isCanceled) break
                    indicator.fraction = idx.toDouble() / findings.size
                    indicator.text = "Pre-warming ${idx + 1} / ${findings.size}: ${issue.type.takeLast(60)}"

                    val response = try {
                        LlmClient.sendRequest(issue, project, "Intermediate")
                    } catch (ex: Exception) {
                        failed++
                        recorder.record(
                            "prewarm.error",
                            findingId = findingId(issue),
                            data = mapOf("message" to (ex.message ?: ex.javaClass.simpleName)),
                        )
                        continue
                    }
                    if (response.isNullOrBlank() || response.startsWith("Error")) {
                        failed++
                        recorder.record(
                            "prewarm.error",
                            findingId = findingId(issue),
                            data = mapOf("response" to (response ?: "").take(200)),
                        )
                        continue
                    }
                    issue.explanation = response
                    ok++
                }

                recorder.record(
                    "prewarm.completed",
                    data = mapOf(
                        "succeeded" to ok,
                        "failed" to failed,
                        "cancelled" to indicator.isCanceled,
                        "level" to "Intermediate",
                    ),
                )
            }
        })
    }

    private fun findingId(issue: Issue): String =
        "${issue.type}::${issue.location.fileName}::${issue.location.startLine}::${issue.location.endLine}"
}
