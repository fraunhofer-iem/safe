package de.fraunhofer.iem.fixmysast.comm

import com.intellij.util.messages.Topic
import de.fraunhofer.iem.fixmysast.sast.Issue

interface ResultNotifier {

    companion object {

        val SHOW_RESULT_TOPIC: Topic<ResultNotifier> =
            Topic.create<ResultNotifier>(
                "Show result",
                ResultNotifier::class.java
            )
    }

    fun showResult(
        issue: Issue
    )
}