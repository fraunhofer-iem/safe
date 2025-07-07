# FixMySAST

## How to test

- Open the project in the intelIJ IDE
- Rename the file `app.properties_template` to `app.properties` in the resource directory (`srm/main/resource/`)
- Place your Fraunhofer LLM API key to the `api.key` in `app.properties` file
- Place the openAi URL and Olama URL in the `app.properties`
- Select the model using the key `llm.model`. The supported values are `openai` for OpenAI model and `olama` for Olama model.
- Run the plugin from the run option on the top.