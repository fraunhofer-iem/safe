package de.fraunhofer.iem.safe.comm

import com.intellij.util.messages.Topic
import de.fraunhofer.iem.safe.sast.Issue

interface ExplanationNotifier {

    companion object {

        val SHOW_EXPLANATION_TOPIC: Topic<ExplanationNotifier> =
            Topic.create<ExplanationNotifier>(
                "Show explanation",
                ExplanationNotifier::class.java
            )
    }

    fun showExplanation(
        issue: Issue
    )
}