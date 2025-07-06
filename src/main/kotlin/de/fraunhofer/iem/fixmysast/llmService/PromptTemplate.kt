package de.fraunhofer.iem.fixmysast.llmService

import de.fraunhofer.iem.fixmysast.sast.dataModel.ExpertiseLevel
import de.fraunhofer.iem.fixmysast.sast.dataModel.SASTIssue

object PromptTemplate {
    fun build(issue: SASTIssue, level: ExpertiseLevel): String = when (level) {
        ExpertiseLevel.BEGINNER -> buildBeginnerPrompt(issue)
        ExpertiseLevel.INTERMEDIATE -> buildIntermediatePrompt(issue)
        ExpertiseLevel.ADVANCED -> buildAdvancedPrompt(issue)
    }

    private fun buildBeginnerPrompt(issue: SASTIssue): String {
        return """
                You are an expert in software security. Explain the given error from the static analysis tool to a novice software developer in a way that helps them understand the security issue.
                 
                ---
                Follow the output format strictly.
                The explanation must not exceed 500 words. 
                Provide the explanation as a basic string value. 
                Do not add any other unique characters to the block section, ie: triple backticks or triple quotes. Do not include scalars.
                You're CodeFixSuggestion must be the code fix to the original code that contains the issue found by the static analysis tool. This code fix suggestion is not for the example code provided by you.
                Your response must be a YAML formatted document with these top-level keys:
                 
                Explanation: explanation of the given error
                ExampleCode: simple code snippet illustrating the issue
                ExampleCodeExplanation: Explanation of the above provided simple code snippet example by you
                CodeFixSuggestion: code fix suggestion to resolve the given error. This code must be the code fix to the original code that contains the issue found by the static analysis tool. This code fix suggestion is not for the example code provided by you.
                CodeFixSuggestionExplanation: explanation of the provided code fix suggestion
                
                ---
                
                Here is an example of the correct output:
                
                Explanation: "explanation of the given error."
                ExampleCode: |
                  public void exampleMethod() {
                      System.out.println("This is the example code.");
                  }
                ExampleCodeExplanation: "explanation of the example in the field ExampleCode"
                CodeFixSuggestion: |
                  ...
                  System.out.println("This is the code fix suggestion to the code in the static analysis tool.");
                  ...
                CodeFixSuggestionExplanation: "Explanation of the provide code fix suggestion in the field CodeFixSuggestion."
                 
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
                 
                Error Tag:
                ```
                ${issue.tags[0]}
                ```
                 
                Replace this with a secure code fix:
                ```
                ${issue.codeSnippet}
                ```
        """.trimIndent()
    }

    private fun buildIntermediatePrompt(issue: SASTIssue): String {
        return """
            You are an expert in software security. Analyze the static analysis tool output and provide a clear, technically sound explanation suitable for an intermediate-level developer. Focus on helping them understand the underlying cause, security implications, and mitigation strategy.
 
            ---
            Follow the output format strictly.
            The explanation must not exceed 500 words.
            Provide the explanation as a basic string value.
            Do not add any other unique characters to the block section, ie: triple backticks or triple quotes. Do not include scalars.
            You're CodeFixSuggestion must be the code fix to the original code that contains the issue found by the static analysis tool. This code fix suggestion is not for the example code provided by you.
            Your response must be a YAML formatted document with these top-level keys:
             
            Explanation: concise technical explanation of the given error
            ExampleCode: representative code snippet illustrating the issue
            ExampleCodeExplanation: Explanation of the above provided simple code snippet example by you
            CodeFixSuggestion: code fix suggestion to resolve the given error. This code must be the code fix to the original code that contains the issue found by the static analysis tool. This code fix suggestion is not for the example code provided by you.
            CodeFixSuggestionExplanation: explanation of the provided code fix suggestion
            
            ---
                
            Here is an example of the correct output:
                
            Explanation: "explanation of the given error."
            ExampleCode: |
              public void exampleMethod() {
                System.out.println("Hello, world!");
              }
            ExampleCodeExplanation: "explanation of the example in the field ExampleCode"
            CodeFixSuggestion: |
              ...
              System.out.println("This is the code fix suggestion to the code in the static analysis tool.");
              ...
            CodeFixSuggestionExplanation: "Explanation of the provide code fix suggestion in the field CodeFixSuggestion."
             
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
             
            Error Tag:
            ```
            ${issue.tags[0]}
            ```
             
            Replace this with a secure code fix:
            ```
            ${issue.codeSnippet}
            ```
        """.trimIndent()
    }

    private fun buildAdvancedPrompt(issue: SASTIssue): String {
        return """
            You are an expert in software security. Explain the given error from the static analysis tool to an advanced-level developer in a way that helps them understand the security issue.
 
                ---
                Follow the output format strictly.
                The explanation must not exceed 500 words. 
                Provide the explanation as a basic string value. 
                Do not add any other unique characters to the block section, ie: triple backticks or triple quotes. Do not include scalars.
                Your response must be a YAML formatted document with these top-level keys:
                 
                Explanation: explanation of the given error
                CodeFixSuggestion: code fix suggestion to resolve the given error. This code must be the code fix to the original code that contains the issue found by the static analysis tool. This code fix suggestion is not for the example code provided by you. Do not halli
                CodeFixSuggestionExplanation: explanation of the provided code fix suggestion
                
                ---
                
                Here is an example of the correct output:
                
                Explanation: "explanation of the given error."
                CodeFixSuggestion: |
                  ...
                  System.out.println("This is the code fix suggestion to the code in the static analysis tool.");
                  ...
                CodeFixSuggestionExplanation: "Explanation of the provide code fix suggestion in CodeFixSuggestion."
                 
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
                 
                Error Tag:
                ```
                ${issue.tags[0]}
                ```
                 
                Replace this with a secure code fix:
                ```
                ${issue.codeSnippet}
                ```
        """.trimIndent()
    }
}
