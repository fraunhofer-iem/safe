package de.fraunhofer.iem.fixmysast.llm

import de.fraunhofer.iem.fixmysast.sast.Issue

/**
 * Prompt templates for the explanation of the SAST issue and provides the prompts based on the developers expertise level.
 *
 * @author Alexandra Fomina
 * @author Ranjith
 */
object PromptTemplate {
    fun build(issue: Issue, level: Int): String {
        return """
            You are an expert in software security. Analyze the static analysis tool output and provide a clear, technically sound explanation suitable for a developer who marked their skills a $level out of 10. 
            Focus on helping them understand the underlying cause, security implications, and mitigation strategy.
 
            ---
            You must follow the below guidelines:
            - The explanation must not exceed 750 words.
            - Provide the explanation as a basic string value.
            - Do not add any other unique characters to the block section, ie: triple backticks or triple quotes. Do not include scalars.
            
            Your response must be a YAML formatted document with these top-level keys:
             
            Overview: Generic overview of the issue (${if (issue.tags.isNotEmpty()) issue.tags[0] else ""}) in simple words.
            Explanation: concise technical explanation of the error found in the original code
            ExampleCode: representative new code snippet illustrating the issue
            ExampleCodeExplanation: Explanation of the above provided simple code snippet example by you
            
            ---
                
            Here is an example of the correct output:
                
            Overview: "generic overview of the issue (${if (issue.tags.isNotEmpty()) issue.tags[0] else ""}) in simple words."
            Explanation: "concise technical explanation of the error found in the original code."
            ExampleCode: |
              public void exampleMethod() {
                System.out.println("Hello, world!");
              }
            ExampleCodeExplanation: "explanation of the example in the field ExampleCode"
             
            ---
            
            
            Below is the information provided by the static analysis tool:
             
            Error Type:
            ```
            ${issue.type}
            ```
             
            Error Description:
            ```
            ${issue.message}
            ```
             
            ${
            if (issue.tags.isNotEmpty()) """
                    |Error Tag:
                    |```
                    |${issue.tags[0]}
                    |```
                """.trimMargin() else ""
        }
             
            Original code where the issue was found by the static analysis tool:
            ```
            ${issue.location.codeSnippet}
            ```
        """.trimIndent()
    }

    fun update(issue: Issue, level: Int): String {
        var update = """
        Previously, you have provided the below explanation for upper issue. 
        The user has rated this as a poor explanation.
        Can you please provide a better explanation for the same issue with the output guideline as provided above.
        ---
        ${issue.explanation}
        ---
        Please improve it for clarity and usefulness."""
        println("Current level is $level")
        return build(issue, level) + update
    }
}
