package de.fraunhofer.iem.fixmysast.comm

import com.intellij.util.messages.Topic
import de.fraunhofer.iem.fixmysast.sast.Results

interface ParseFileNotifier {

    companion object {
        val PARSE_SARIF_FILE: Topic<ParseFileNotifier> =
            Topic.create<ParseFileNotifier>("Load SAST file",
                ParseFileNotifier::class.java)
    }

    fun parse(sastFile: String)

}
