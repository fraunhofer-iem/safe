package de.fraunhofer.iem.fixmysast.llm

import com.thoughtworks.xstream.mapper.Mapper
import de.fraunhofer.iem.fixmysast.sast.Issue

/**
 * Prompt templates for the explanation of the SAST issue and provides the prompts based on the developers expertise level.
 *
 * @author Alexandra Fomina
 * @author Ranjith
 */
object PromptTemplate {
    fun build(issue: Issue, level: ExpertiseLevel): String = when (level) {
        ExpertiseLevel.BEGINNER -> buildBeginnerPrompt(issue)
        ExpertiseLevel.INTERMEDIATE -> buildIntermediatePrompt(issue)
        ExpertiseLevel.ADVANCED -> buildAdvancedPrompt(issue)
    }

    fun update(issue: Issue, level: ExpertiseLevel): String{
        println("Inside the update Prompt Function!")
        var update = """
        Previously, you have provided the below explanation for upper issue. 
        The user has rated this as a poor explanation.
        Can you please provide a better explanation for the same issue with the output guideline as provided above.
        ---
        ${issue.explanation}
        ---
        Please improve it for clarity and usefulness."""

        var finalPrompt = ""

        if(level == ExpertiseLevel.BEGINNER)
        {
            finalPrompt = buildBeginnerPrompt(issue) + update
        }
        else if(level == ExpertiseLevel.INTERMEDIATE){
            finalPrompt = buildIntermediatePrompt(issue) + update
        }
        else{
            finalPrompt = buildAdvancedPrompt(issue) + update
        }
        return finalPrompt
    }

    private fun buildBeginnerPrompt(issue: Issue): String {
        return """
                You are an expert in software security. Explain the given error from the static analysis tool to a novice software developer in a way that helps them understand the security issue.
                 You are an expert in software security. Analyze the static analysis tool output and provide a clear, technically sound explanation suitable for an NOVICE DEVELOPER. Focus on helping them understand the underlying cause, security implications, and mitigation strategy.
                ---
                You must follow the below guidelines:
                - The explanation must not exceed 750 words. 
                - Provide the explanation as a basic string value. 
                - Do not add any other unique characters to the block section, ie: triple backticks or triple quotes. Do not include scalars.
                Your response must be a YAML formatted document with these top-level keys:
                 
                Explanation: explanation of the given error
                ExampleCode: simple code snippet illustrating the issue
                ExampleCodeExplanation: Explanation of the above provided simple code snippet example by you
                
                ---
                
                Here is an example of the correct output:
                
                Explanation: "explanation of the given error."
                ExampleCode: |
                  public void exampleMethod() {
                      System.out.println("This is the example code.");
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

    private fun buildIntermediatePrompt(issue: Issue): String {
        return """
            You are an expert in software security. Analyze the static analysis tool output and provide a clear, technically sound explanation suitable for an INTERMEDIATE-LEVEL DEVELOPER. Focus on helping them understand the underlying cause, security implications, and mitigation strategy.
 
            ---
            You must follow the below guidelines:
            - The explanation must not exceed 750 words.
            - Provide the explanation as a basic string value.
            - Do not add any other unique characters to the block section, ie: triple backticks or triple quotes. Do not include scalars.
            
            Your response must be a YAML formatted document with these top-level keys:
             
            Explanation: concise technical explanation of the given error
            ExampleCode: representative code snippet illustrating the issue
            ExampleCodeExplanation: Explanation of the above provided simple code snippet example by you
            
            ---
                
            Here is an example of the correct output:
                
            Explanation: "explanation of the given error."
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

    private fun buildAdvancedPrompt(issue: Issue): String {
        return """
            You are an expert in software security. Analyze the static analysis tool output and provide a clear, technically sound explanation suitable for an ADVANCED-LEVEL DEVELOPER. Focus on helping them understand the underlying cause, security implications, and mitigation strategy.
                ---
                You must follow the below guidelines:
                - The explanation must not exceed 750 words. 
                - Provide the explanation as a basic string value. 
                - Do not add any other unique characters to the block section, ie: triple backticks or triple quotes. Do not include scalars.
                
                Your response must be a YAML formatted document with these top-level keys:
                 
                Explanation: explanation of the given error
                
                ---
                
                Here is an example of the correct output:
                
                Explanation: "explanation of the given error."
                 
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
}
