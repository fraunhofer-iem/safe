package de.fraunhofer.iem.safe.comm

import com.intellij.util.messages.Topic
import de.fraunhofer.iem.safe.sast.Issue

interface DataflowNotifier {
    companion object {
        val SHOW_EDITOR_TOPIC: Topic<DataflowNotifier> = Topic.create<DataflowNotifier>(
            "Show editor",
            DataflowNotifier::class.java
        )
    }

    fun showEditor(
        issue: Issue
    )
}