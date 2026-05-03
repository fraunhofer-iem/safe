package de.fraunhofer.iem.safe.sast

/** Role of a [TaintStep] within its containing trace. */
enum class StepRole { SOURCE, SINK, CALL, PROPAGATOR, SANITIZER, UNKNOWN }

/**
 * Resolves a step's role from its [TaintStep.message] (Semgrep tags steps with
 * "Source:", "Sink:", "Call:", "Propagator:", or "Sanitizer:" prefixes), falling back
 * to position within the trace when the message has no role tag (Qodana traces).
 */
object StepRoleResolver {
    fun resolve(message: String?, index: Int, total: Int): StepRole {
        val lower = message?.trimStart()?.lowercase().orEmpty()
        return when {
            lower.startsWith("source") -> StepRole.SOURCE
            lower.startsWith("sink") -> StepRole.SINK
            lower.startsWith("call") -> StepRole.CALL
            lower.startsWith("propagator") -> StepRole.PROPAGATOR
            lower.startsWith("sanitizer") -> StepRole.SANITIZER
            total <= 0 -> StepRole.UNKNOWN
            index == 0 -> StepRole.SOURCE
            index == total - 1 -> StepRole.SINK
            else -> StepRole.PROPAGATOR
        }
    }
}
