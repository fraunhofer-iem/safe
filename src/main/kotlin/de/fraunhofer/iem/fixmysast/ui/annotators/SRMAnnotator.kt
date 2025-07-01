package de.fraunhofer.iem.fixmysast.ui.annotators

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.util.FunctionUtil
import de.fraunhofer.iem.fixmysast.analysis.SRMFinder
import de.fraunhofer.iem.fixmysast.data.Constants
import de.fraunhofer.iem.fixmysast.icons.IconUtils
import de.fraunhofer.iem.fixmysast.ui.highlighter.SRMHighlighting.SRM_HIGHLIGHT
import de.fraunhofer.iem.fixmysast.util.MethodUtil
import java.util.function.Supplier

/***
 * Annotates known SRMs in the editor with a tooltip text and adds gutter icons.
 ***/
class SRMAnnotator: Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element is PsiMethodCallExpression) {
            val method = element.resolveMethod() ?: return
            val methodSignature = MethodUtil.getMethodSignature(method)

            if (SRMFinder.isSRM(methodSignature)) {
                val annotatorTooltipText = Constants.SRM_TOOLTIP_TEMPLATE.format(methodSignature, SRMFinder.getSrmAndCweCategory(methodSignature).joinToString(","))
                val gutterTooltipText = SRMFinder.getSrmAndCweCategory(methodSignature).joinToString(",")

                val lineMarkerInfo = LineMarkerInfo(
                    element,
                    element.textRange,
                    IconUtils.getMethodSRMGutterIcon(methodSignature),
                    FunctionUtil.constant(gutterTooltipText),
                    null,
                    GutterIconRenderer.Alignment.RIGHT,
                    Supplier { "SRM method marker for $methodSignature" }
                )

                holder.newAnnotation(HighlightSeverity.WARNING, annotatorTooltipText)
                .range(element.methodExpression.textRange)
                .gutterIconRenderer(LineMarkerInfo.LineMarkerGutterIconRenderer(lineMarkerInfo))
                .textAttributes(SRM_HIGHLIGHT)
                .create()
            }
        }
    }
}