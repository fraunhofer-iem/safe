package de.fraunhofer.iem.safe.ui.srm

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

object SrmHighlighter {
    val SRM_HIGHLIGHT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SRM_HIGHLIGHT",
        DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT_HIGHLIGHTED
    )
}