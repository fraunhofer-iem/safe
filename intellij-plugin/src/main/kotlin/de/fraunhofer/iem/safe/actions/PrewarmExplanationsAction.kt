package de.fraunhofer.iem.safe.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import de.fraunhofer.iem.safe.llm.ExplainRequest
import de.fraunhofer.iem.safe.llm.LlmProviderFactory
import de.fraunhofer.iem.safe.llm.SafeLlmSettings
import de.fraunhofer.iem.safe.sast.VulnerabilityInfo
import de.fraunhofer.iem.safe.study.StudyModeSettings
import de.fraunhofer.iem.safe.study.TelemetryRecorder
import de.fraunhofer.iem.safe.ui.ExplanationCacheService
import de.fraunhofer.iem.safe.ui.ExplanationStorageService

/**
 * Toolbar / menu action that walks every imported finding and asks the active
 * provider for an explanation, writing each response into the persistent cache
 * so participants can navigate the tree without waiting on the LLM during the
 * study task. Only visible when [StudyModeSettings.enabled] is true.
 *
 * Pre-warming is sequential and bounded — `Task.Backgroundable` shows progress
 * in the status bar and the user can cancel partway. Failures of individual
 * findings are caught so one bad call doesn't abort the whole run; the
 * `prewarm.completed` telemetry event reports the success / failure tally.
 */
class PrewarmExplanationsAction : AnAction(
    "Pre-warm Explanations",
    "Generate explanations for every imported finding and store them in the cache (study mode)",
    AllIcons.Actions.Compile,
) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = StudyModeSettings.getInstance().enabled
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val findings = collectFindings(project)
        if (findings.isEmpty()) return

        val recorder = TelemetryRecorder.getInstance(project)
        recorder.record("prewarm.started", data = mapOf("count" to findings.size))

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Pre-warming SAFE explanations…", true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val provider = LlmProviderFactory.current()
                val providerId = SafeLlmSettings.getInstance().providerKind.id
                val cache = ExplanationCacheService.getInstance(project)

                var ok = 0
                var skipped = 0
                var failed = 0

                for ((idx, finding) in findings.withIndex()) {
                    if (indicator.isCanceled) break
                    indicator.fraction = idx.toDouble() / findings.size
                    val label = finding.inspectionId?.substringAfterLast('.') ?: "finding"
                    indicator.text = "Pre-warming ${idx + 1} / ${findings.size}: $label"

                    // Skip findings the cache already has for this provider — repeated
                    // pre-warm runs across sessions stay cheap.
                    if (cache.find(
                            finding.inspectionId.orEmpty(),
                            finding.filePath,
                            finding.startLine,
                            finding.endLine,
                            providerId,
                        ) != null
                    ) {
                        skipped++
                        continue
                    }

                    val response = try {
                        provider.explain(ExplainRequest(project, listOf(finding)))
                    } catch (ex: Exception) {
                        failed++
                        recorder.record(
                            "prewarm.error",
                            findingId = findingKey(finding),
                            data = mapOf("message" to (ex.message ?: ex.javaClass.simpleName)),
                        )
                        continue
                    }
                    if (response.startsWith("Error:")) {
                        failed++
                        recorder.record(
                            "prewarm.error",
                            findingId = findingKey(finding),
                            data = mapOf("response" to response.take(200)),
                        )
                        continue
                    }

                    cache.store(
                        finding.inspectionId.orEmpty(),
                        finding.cwe?.id,
                        finding.filePath,
                        finding.startLine,
                        finding.endLine,
                        response,
                        providerId,
                    )
                    ok++
                }

                recorder.record(
                    "prewarm.completed",
                    data = mapOf(
                        "succeeded" to ok,
                        "skipped" to skipped,
                        "failed" to failed,
                        "cancelled" to indicator.isCanceled,
                        "provider" to providerId,
                    ),
                )
                // Re-fire the provider-change topic so the SAFE tool window
                // rebuilds its tree from the now-warmer cache. Cheap on the bus
                // and avoids manually duplicating the panel's reload logic here.
                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(de.fraunhofer.iem.safe.llm.SafeProviderChangeListener.TOPIC)
                        .providerChanged(providerId)
                }
            }
        })
    }

    private fun collectFindings(project: Project): List<VulnerabilityInfo> {
        val storage = ExplanationStorageService.getInstance(project)
        return storage.getAll()
            .map { it.vulnerability }
            .distinctBy { findingKey(it) }
            .filter { it.inspectionId != null && it.filePath != null }
    }

    private fun findingKey(v: VulnerabilityInfo): String =
        "${v.inspectionId}::${v.filePath}::${v.startLine ?: 0}::${v.endLine ?: 0}"
}
