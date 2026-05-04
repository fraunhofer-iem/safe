import json
from langchain.agents import create_agent

from langgraph.graph import StateGraph, MessagesState, START, END
from langgraph.prebuilt import ToolNode
from langchain_openai import ChatOpenAI
from langchain_core.messages import SystemMessage

from agents.tools import read_code, get_code_structure, search_codebase, find_files, list_files
from agents.types import ExplainerResponse

def setup_explainer_agent(ctags_path: str, llm, payload, project_root: str):
    """
    Sets up the prompts and tools, and returns the compiled agent graph
    and initial state so the main FastAPI router can stream it.
    """

    # 1. Build the Dynamic User Prompt (Just the data)
    user_prompt_lines = ["Explain the following SAST vulnerability finding(s):", ""]

    if payload.cwe:
        user_prompt_lines.append(f"CWE: {payload.cwe}")
    if payload.rule_id:
        user_prompt_lines.append(f"Inspection ID: {payload.rule_id}")
    if payload.severity:
        user_prompt_lines.append(f"Severity: {payload.severity}")
    if payload.message:
        user_prompt_lines.append(f"Message: {payload.message}")
    if payload.filepath:
        user_prompt_lines.append(f"File: {payload.filepath}")

    if payload.start_line is not None:
        line_info = f"Line: {payload.start_line}"
        if payload.end_line is not None:
            line_info += f"-{payload.end_line}"
        user_prompt_lines.append(line_info)

    if payload.snippet:
        user_prompt_lines.append(f"Code Snippet:\n{payload.snippet}")

    # Notice we removed the buggy trace_guidance logic here, just pass the data!
    if payload.taint_flow:
        user_prompt_lines.append(f"\nData-flow Traces:\n{json.dumps(payload.taint_flow)}")

    user_prompt = "\n".join(user_prompt_lines)

    # 2. Static System Prompt (Instructions + Output Format)
    system_prompt = """
You are an elite Application Security Engineer and SAST expert. Your objective is to analyze a static analysis finding using your tools, and provide an explanation that offers BOTH a quick summary AND an enriched, detailed deep-dive.

CRITICAL RULES & TOOL USAGE:
1. GROUND TRUTH ONLY: NEVER guess or hallucinate code. You MUST use your available tools (`read_code`, `search_codebase`, etc.) to inspect the actual repository before answering.
2. BE REPO-SPECIFIC: Avoid generic textbook definitions. Reference exact variables, function names, and logic used in this codebase.
3. RELATIVE PATHS ONLY: All file paths you provide to tools MUST be relative to the project root (e.g., 'src/main.py').
4. MANDATORY INVESTIGATION: You MUST call `read_code` on the vulnerable file and line provided in the context BEFORE generating your response.
5. TRACE HANDLING: If the user prompt provides Data-flow Traces, you MUST explicitly refer to the trace steps in the WHERE and WHY sections (e.g., "untrusted input enters at step 1 and reaches the sink at step N"). Additionally, populate the `trace_steps` array to give a 1-3 sentence explanation of what happens at EVERY step in the trace. Use the `step_id` format T.S (where T is trace index and S is step index).

OUTPUT FORMAT REQUIREMENTS (TWO LAYERS):
You must populate the structured output in two distinct layers:

LAYER 1: The Quick Summaries (`what`, `where`, `why`, `how`)
- These MUST be strictly 1-2 sentences maximum.
- High information density, zero fluff.
- Also return a title for this specific alert that helps developers to recognize this alert quickly

LAYER 2: The Enriched Deep-Dives (`what_detailed`, `where_detailed`, `why_detailed`, `how_detailed`)
- There is NO sentence limit here. Provide comprehensive, detailed explanations.
- Use this space to explain the underlying mechanics, edge cases, attack scenarios, and detailed code-level remediation logic so the developer has all the technical context they need to understand and fix the issue properly.
"""

    tools = [read_code, get_code_structure, search_codebase, find_files, list_files]

    # 1. Create the compiled agent graph
    agent_graph = create_agent(model=llm, tools=tools, response_format=ExplainerResponse)

    # 2. Prepare the initial state
    initial_state = {
        "messages": [
            ("system", system_prompt),
            ("user", user_prompt),
        ]
    }

    # Return them so FastAPI can run agent_graph.astream_events(initial_state)
    return agent_graph, initial_state

