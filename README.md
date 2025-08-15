## FixMySAST
# About
FixMySAST is an IntelliJ plugin that leverages GPT-4o to explanain the causes, impacts, and mitigation strategies of security vulnerabilities detected by SAST tools. The plugin creates a seperate tool window in the IntelliJ IDE, and uses an expandable tree component to display th edetected vulnerabilities. After clicking a vulnerability in the tree, 3 key features are displayed: the original results from the SAST tool, the explanation from the LLM, and the data-flow, all in their respective tabs. In addition to the Plugin tool window, the data flow information is also shown in the editor’s gutter. The explanation tab
shows the name of the detected vulnerabilities, tags assigned by the SAST tool, description of the result, a demonstrative example, general mitigation strategies extracted from OWASP, and thumbs up/down feedback buttons.
# Installation


