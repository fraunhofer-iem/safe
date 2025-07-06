package de.fraunhofer.iem.fixmysast.comm

import com.intellij.util.messages.Topic
import de.fraunhofer.iem.fixmysast.sast.SASTParsedResult
import de.fraunhofer.iem.fixmysast.sast.dataModel.ExpertiseLevel
import de.fraunhofer.iem.fixmysast.sast.dataModel.SASTIssue

interface LlmApiNotifier {

    companion object {
        val SEND_REQUEST_TOPIC: Topic<LlmApiNotifier> =
            Topic.create<LlmApiNotifier>(
                "Send Request",
                LlmApiNotifier::class.java
            )

        val GET_RESPONSE_TOPIC: Topic<LlmApiNotifier> =
            Topic.create<LlmApiNotifier>(
                "Get response",
                LlmApiNotifier::class.java
            )
    }

    fun sendRequest(results: SASTParsedResult)

    fun getResponse(
        results: SASTParsedResult, issue: SASTIssue,
        level: ExpertiseLevel,
    )
}