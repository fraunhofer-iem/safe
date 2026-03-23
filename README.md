
# SAFE - Static Analysis Findings Explainer

SAFE helps developers understand security findings flagged by Static Application Security Testing (SAST) tools. It supports Static Analysis Results Interchange Format (SARIF) results and uses large language models (LLMs) to explain root causes, potential impacts, and practical mitigations for software vulnerabilities.

![safe-plugin-demo.gif](intellij-plugin/docs/safe-plugin-demo.gif)

## SAFE Plugin Features

- **SARIF support**
  - Import SAST results in SARIF to view findings directly in IntelliJ IDEA plugin tool window. 
- **LLM-powered explanations**
  - For each finding, SAFE displays: vulnerability name, SAST tags, an explanation of the vulnerability, a demonstrative example, and mitigation strategies. 
  - Quick thumbs up/down for feedback on explanation quality. 
- **Audience-aware guidance** 
  - Toggle explanation depth by experience level (beginner, intermediate, advanced). 
- **Data-flow walkthrough**
  - When available from the SAST report, step through the data flow for the selected finding.

## How to Run

Clone the project, import it in IntelliJ IDEA, and edit app.properties to point SAFE to your LLM provider. Rename the [app.properties template](https://github.com/fraunhofer-iem/safe/blob/main/src/main/resources/app.properties_template) file to `app.properties`. Enter the LLM platform, API Key, endpoint and other settings.