package de.fraunhofer.iem.safe.util

import com.intellij.psi.PsiMethod

object MethodUtil {

    /***
     * Returns the method signature for a PsiMethod object.
     ***/
    fun getMethodSignature(method: PsiMethod): String {
        val className = method.containingClass?.qualifiedName ?: return ""
        val methodName = method.name
        val returnType = method.returnType?.presentableText ?: "void"
        val parameterTypes = method.parameterList.parameters.joinToString(", ") {
            it.type.canonicalText
        }

        return "$returnType $className.$methodName($parameterTypes)"
    }
}