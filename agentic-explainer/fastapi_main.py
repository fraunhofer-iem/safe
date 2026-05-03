import os
import json
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse

# 1. Import your external types and agent
from agents.types import ExplainerRequest
from agents.explainer_agent import setup_explainer_agent
# from createCtags import build_index
from langchain_openai import ChatOpenAI
from langfuse.langchain import CallbackHandler
from langchain_openai import AzureChatOpenAI
from dotenv import load_dotenv

from createCtags import build_index

app = FastAPI(title="SAFE Agent Backend")
app.state.current_root_path = None
ctags_path = "ctags_code_structure.json"
load_dotenv()

langfuse_handler = CallbackHandler()

LLM = AzureChatOpenAI(
    azure_endpoint=os.getenv("AZURE_OPENAI_ENDPOINT"),
    api_key=os.getenv("AZURE_OPENAI_API_KEY"),
    api_version=os.getenv("AZURE_OPENAI_API_VERSION"),
    deployment_name=os.getenv("AZURE_OPENAI_DEPLOYMENT"),
    streaming=True
)

@app.get("/health")
async def health_check():
    return {"status": "ok", "current_root_path": app.state.current_root_path}

@app.post("/explain")
async def explain_vuln(request: Request, payload: ExplainerRequest):
    if not os.path.exists(payload.rootpath) or not os.path.isdir(payload.rootpath):
        raise HTTPException(status_code=404, detail=f"Directory not found: {payload.rootpath}")

    request.app.state.current_root_path = payload.rootpath
    build_index(request.app.state.current_root_path, ctags_path)

    # Enable streaming on the LLM
    # 2. Get the graph and state from your agent file (NO prompts here)
    agent_graph, initial_state = setup_explainer_agent(
        ctags_path=ctags_path,
        llm=LLM,
        payload=payload,
        project_root=payload.rootpath
    )

    agent_config = {
        "callbacks": [langfuse_handler],
        "run_name": "explainer",
        "tags": ["agent:explainer-agent"],
        "metadata": {
            "agent": "explainer-agent",
            "ctags_path": ctags_path,
            "project_root": payload.rootpath
        }
    }

    async def event_streamer():
        try:
            async for event in agent_graph.astream_events(initial_state, config=agent_config, version="v2"):
                kind = event["event"]

                if kind == "on_tool_start":
                    data = json.dumps({
                        "type": "tool_start",
                        "tool": event["name"],
                        "input": event["data"].get("input")
                    })
                    yield f"data: {data}\n\n"

                elif kind == "on_chat_model_stream":
                    chunk = event["data"]["chunk"]

                    # Structured output usually streams inside tool_call_chunks
                    if hasattr(chunk, "tool_call_chunks") and chunk.tool_call_chunks:
                        for tc_chunk in chunk.tool_call_chunks:
                            if "args" in tc_chunk and tc_chunk["args"]:
                                safe_content = tc_chunk["args"].replace("\n", "\\n")
                                data = json.dumps({"type": "token", "content": safe_content})
                                yield f"data: {data}\n\n"

                    elif chunk.content:
                        safe_content = chunk.content.replace("\n", "\\n")
                        data = json.dumps({"type": "token", "content": safe_content})
                        yield f"data: {data}\n\n"

                elif kind == "on_chain_end" and event.get("name") == "LangGraph":
                    yield "data: {\"type\": \"done\"}\n\n"

        except Exception as e:
            error_data = json.dumps({"type": "error", "message": str(e)})
            yield f"data: {error_data}\n\n"

    return StreamingResponse(
        event_streamer(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8800)