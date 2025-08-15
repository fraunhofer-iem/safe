## FixMySAST
### About
FixMySAST is an IntelliJ plugin that utilizes GPT-4o to explain the causes, impacts, and mitigation strategies of security vulnerabilities detected by SAST tools. The plugin creates a separate tool window in the IntelliJ IDE, and uses an expandable tree component to display the detected vulnerabilities. After clicking a vulnerability in the tree, the right-hand panel will display 3 key features: the original results from the SAST tool, the explanation from the LLM, and the data-flow, all in their respective tabs. The explanation tab shows the name of the detected vulnerabilities, tags assigned by the SAST tool, description of the result, a demonstrative example, general mitigation strategies extracted from OWASP, and thumbs up/down feedback buttons. The plugin also allows users to upload specific SARIF/JSON files for analysis, and has a toggle allowing users to change the expertise level of the explanations.
### Installation
1. Download the pluginn
2. In IntelliJ IDEA, go to File -> Settings -> Plugins, and Install Plugin from Disk.
3. Open the project containing the source code you want to analyze, and go to Tools -> FixMySAST. Click on any issue in the left panel to see the AI-generated explanation.

