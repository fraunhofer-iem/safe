package de.fraunhofer.iem.safe.comm

import com.intellij.util.messages.Topic

interface ParseFileNotifier {

    companion object {
        val PARSE_SARIF_FILE: Topic<ParseFileNotifier> =
            Topic.create<ParseFileNotifier>("Load SAST file",
                ParseFileNotifier::class.java)
    }

    fun parse(sastFile: String)

}
