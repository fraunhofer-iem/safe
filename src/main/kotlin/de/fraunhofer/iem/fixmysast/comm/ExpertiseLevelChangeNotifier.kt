package de.fraunhofer.iem.fixmysast.comm

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import de.fraunhofer.iem.fixmysast.llm.ExpertiseLevel

interface ExpertiseLevelChangeNotifier {
    fun onExpertiseLevelChanged(project: Project)
}

val EXPERTISE_LEVEL_CHANGE_TOPIC = Topic.create(
    "ExpertiseLevelChangeNotifier",
    ExpertiseLevelChangeNotifier::class.java
)