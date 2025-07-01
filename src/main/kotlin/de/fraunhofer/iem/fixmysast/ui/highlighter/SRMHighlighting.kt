package de.fraunhofer.iem.fixmysast.ui.highlighter

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

object SRMHighlighting {
    val SRM_HIGHLIGHT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SRM_HIGHLIGHT",
        DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT_HIGHLIGHTED
    )
}