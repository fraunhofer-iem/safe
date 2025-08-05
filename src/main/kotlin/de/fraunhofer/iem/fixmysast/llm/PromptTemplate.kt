package de.fraunhofer.iem.fixmysast.llm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import de.fraunhofer.iem.fixmysast.sast.Issue
import java.io.File

/**
 * Prompt templates for the explanation of the SAST issue and provides the prompts based on the developers expertise level.
 *
 * @author Alexandra Fomina
 * @author Ranjith
 */
object PromptTemplate {

    fun getSystemPrompt(): String {
        return """You are an assistant with expertise in explaining software security vulnerabilities in code snippets. You will be given a code snippet and the result from a static analysis security testing tool for the code snippet. Your task is to explain the static analysis result to a software developer based on their estimated their software security experience out of 10. Provide information about the underlying cause, consequences and mitigation strategies for the reported vulnerability.

When providing a response, follow the below guidelines:
- The explanation must not exceed 750 words.
- Provide the explanation as a basic string value.
- Do not add any other unique characters to the block section, i.e.: triple backticks or triple quotes. Do not include scalars.
- Format the response as a YAML document using the schema below:

---
overview: "<overview of the detected vulnerability in 2-3 sentences>"
explanation: "<explanation of the vulnerability in the code snippet>"
example: "<generic code snippet along with a description of the code snippet>"
exampleDescription: "<description of example code snippet>"
"""
    }

    fun buildUserPrompt(issue: Issue?, level: String, project: Project): String {

        val dataFlows: StringBuilder = StringBuilder()
        if (issue?.hasDataFlowTrace == true )
            for (dataflow in issue?.dataFlowTrace!!)
                dataFlows.append(dataflow.type.toString() + ": " + dataflow.name + "\n")

        val methodBody = getMethodCode(issue, project)

        return """Explain the vulnerability detected in the code snippet to a developer who has $level experience with in software security.

Code Snippet:
```
$methodBody
```

Vulnerability detected:
```
${issue?.type}: ${issue?.message}
```

Line with vulnerability:
```
${issue?.location?.codeSnippet}
```

Data-flow trace:
```
$dataFlows
```

"""
    }

    fun getMethodCode(issue: Issue?, project: Project): String {

        var methodBody: String = ""

        ApplicationManager.getApplication().runReadAction {
            //Get PSI element location
            val files = FilenameIndex.getVirtualFilesByName(
                issue?.location?.fileName?.substringAfterLast(File.separator) ?: "",
                GlobalSearchScope.projectScope(project)
            )

            for (file in files) {
                val psiJavaFile = PsiManager.getInstance(project).findFile(file) as PsiJavaFile?
                for (psiClass in psiJavaFile!!.getClasses()) {

                    for (psiMethod in psiClass.getMethods()) {

                        if (psiMethod.body!!.text.contains(issue?.location?.codeSnippet?.trim() ?:""))
                            methodBody = psiMethod.text
                    }
                }
            }
        }

        return methodBody
    }

    fun update(issue: Issue, level: String, project: Project): String {
        val update = """
        Previously, you have provided the below explanation for upper issue. 
        The user has rated this as a poor explanation.
        Can you please provide a better explanation for the same issue with the output guideline as provided above.
        ---
        ${issue.explanation}
        ---
        Please improve it for clarity and usefulness."""

        return buildUserPrompt(issue, level, project) + update
    }
}
