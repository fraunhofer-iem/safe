package de.fraunhofer.iem.fixmysast.comm

import com.intellij.util.messages.Topic
import de.fraunhofer.iem.fixmysast.sast.Issue

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