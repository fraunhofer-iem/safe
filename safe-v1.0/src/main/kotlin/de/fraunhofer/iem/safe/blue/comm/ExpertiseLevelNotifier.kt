package de.fraunhofer.iem.safe.blue.comm

import com.intellij.util.messages.Topic

interface ExpertiseLevelNotifier {
    companion object {
        val CHANGE_LEVEL_TOPIC = Topic.create(
            "ExpertiseLevelChangeNotifier",
            ExpertiseLevelNotifier::class.java
        )
    }

    fun changeLevel(level: String)
}

