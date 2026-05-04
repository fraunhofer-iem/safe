import os
import json
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse

from agents.types import ExplainerRequest
from agents.explainer_agent import setup_explainer_agent
from langchain_openai import ChatOpenAI, AzureChatOpenAI
from langfuse.langchain import CallbackHandler
from dotenv import load_dotenv

from createCtags import build_index

app = FastAPI(title="SAFE Agent Backend")
app.state.current_root_path = None
ctags_path = "ctags_code_structure.json"
load_dotenv()

langfuse_handler = CallbackHandler()


def _default_llm():
    """Env-var-backed fallback used when the request body has no `llm_provider` field."""
    return AzureChatOpenAI(
        azure_endpoint=os.getenv("AZURE_OPENAI_ENDPOINT"),
        api_key=os.getenv("AZURE_OPENAI_API_KEY"),
        api_version=os.getenv("AZURE_OPENAI_API_VERSION"),
        deployment_name=os.getenv("AZURE_OPENAI_DEPLOYMENT"),
        streaming=True,
    )


DEFAULT_LLM = _default_llm()


def build_llm_from_payload(payload: ExplainerRequest):
    """
    If the request specifies `llm_provider` (and the credentials needed for it), build a
    fresh LangChain LLM for that request. Otherwise fall back to [DEFAULT_LLM].

    Anthropic and Ollama require optional packages (`langchain-anthropic`,
    `langchain-ollama`). If those aren't installed but the caller asks for them, we raise
    an HTTPException with a clear message so the plugin can surface it.
    """
    provider = (payload.llm_provider or "").strip().lower()
    if not provider:
        return DEFAULT_LLM

    temperature = payload.llm_temperature
    model = payload.llm_model or ""
    api_key = payload.llm_api_key or ""
    endpoint = payload.llm_endpoint or ""

    if provider == "azure-openai":
        if not (endpoint and model and api_key):
            raise HTTPException(
                status_code=400,
                detail="Azure OpenAI backend requires llm_endpoint, llm_model, and llm_api_key.",
            )
        return AzureChatOpenAI(
            azure_endpoint=endpoint,
            api_key=api_key,
            api_version=os.getenv("AZURE_OPENAI_API_VERSION", "2024-08-01-preview"),
            deployment_name=model,
            temperature=temperature if temperature is not None else 0.0,
            streaming=True,
        )

    if provider == "openai":
        if not (model and api_key):
            raise HTTPException(
                status_code=400,
                detail="OpenAI backend requires llm_model and llm_api_key.",
            )
        kwargs = dict(model=model, api_key=api_key, streaming=True)
        if endpoint:
            kwargs["base_url"] = endpoint
        if temperature is not None:
            kwargs["temperature"] = temperature
        return ChatOpenAI(**kwargs)

    if provider == "anthropic":
        try:
            from langchain_anthropic import ChatAnthropic  # type: ignore
        except ImportError:
            raise HTTPException(
                status_code=501,
                detail="Anthropic backend requested but `langchain-anthropic` is not installed in the service.",
            )
        if not (model and api_key):
            raise HTTPException(
                status_code=400,
                detail="Anthropic backend requires llm_model and llm_api_key.",
            )
        kwargs = dict(model=model, api_key=api_key, streaming=True)
        if temperature is not None:
            kwargs["temperature"] = temperature
        return ChatAnthropic(**kwargs)

    if provider == "ollama":
        try:
            from langchain_ollama import ChatOllama  # type: ignore
        except ImportError:
            raise HTTPException(
                status_code=501,
                detail="Ollama backend requested but `langchain-ollama` is not installed in the service.",
            )
        if not model:
            raise HTTPException(
                status_code=400,
                detail="Ollama backend requires llm_model.",
            )
        kwargs = dict(model=model)
        if endpoint:
            kwargs["base_url"] = endpoint
        if temperature is not None:
            kwargs["temperature"] = temperature
        return ChatOllama(**kwargs)

    raise HTTPException(status_code=400, detail=f"Unknown llm_provider: {provider!r}")


@app.get("/health")
async def health_check():
    return {"status": "ok", "current_root_path": app.state.current_root_path}

@app.post("/explain")
async def explain_vuln(request: Request, payload: ExplainerRequest):
    if not os.path.exists(payload.rootpath) or not os.path.isdir(payload.rootpath):
        raise HTTPException(status_code=404, detail=f"Directory not found: {payload.rootpath}")

    request.app.state.current_root_path = payload.rootpath
    build_index(request.app.state.current_root_path, ctags_path)

    llm = build_llm_from_payload(payload)

    agent_graph, initial_state = setup_explainer_agent(
        ctags_path=ctags_path,
        llm=llm,
        payload=payload,
        project_root=payload.rootpath,
    )

    agent_config = {
        "callbacks": [langfuse_handler],
        "run_name": "explainer",
        "tags": ["agent:explainer-agent"],
        "metadata": {
            "agent": "explainer-agent",
            "ctags_path": ctags_path,
            "project_root": payload.rootpath,
            "llm_provider": payload.llm_provider or "service-default",
        },
        "recursion_limit": 100
    }

    async def event_streamer():
        try:
            async for event in agent_graph.astream_events(initial_state, config=agent_config, version="v2"):
                kind = event["event"]

                if kind == "on_tool_start":
                    data = json.dumps({
                        "type": "tool_start",
                        "tool": event["name"],
                        "input": event["data"].get("input"),
                    })
                    yield f"data: {data}\n\n"

                elif kind == "on_chat_model_stream":
                    chunk = event["data"]["chunk"]

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
            "X-Accel-Buffering": "no",
        },
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8800)