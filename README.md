
## FixMySAST

FixMySAST is an IntelliJ plugin that utilizes large language models to explain the causes, impacts, and mitigation strategies of security vulnerabilities detected by SAST tools. The plugin creates a separate tool window in the IntelliJ IDE, and uses an expandable tree component to display the detected vulnerabilities. After clicking a vulnerability in the tree, the right-hand panel will display 3 key features: the original results from the SAST tool, the explanation from the LLM, and the data-flow, all in their respective tabs. The explanation tab shows the name of the detected vulnerabilities, tags assigned by the SAST tool, description of the result, a demonstrative example, general mitigation strategies extracted from OWASP, and thumbs up/down feedback buttons. The plugin also allows users to upload specific SARIF/JSON files for analysis, and has a toggle allowing users to change the expertise level of the explanations.


### Reposistory Structure

gradle:
src:

### Getting Started

- Clone the project and open it in IntelliJ IDEA
- Rename the teplate `srm/main/resource/app.properties_template` file to `app.properties`
- Update the OpenAI/Ollama API Key, URL and temeperature in `app.properties` file
- Place the openAi URL and Olama URL in the `app.properties`
- Select the model using the key `llm.model`. The supported values are `openai` for OpenAI model and `olama` for Olama model.
- Run the plugin from the run option on the top.


- In IntelliJ IDEA, go to File -> Settings -> Plugins, and Install Plugin from Disk.