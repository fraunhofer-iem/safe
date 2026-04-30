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
    # 1. Format the taint flow nicely if it exists
    taint_flow_section = ""
    if payload.taint_flow:
        taint_flow_section = (
            "### TAINT FLOW (Source to Sink):\n"
            f"{json.dumps(payload.taint_flow, indent=2)}\n"
        )

    # 2. Build a structured, delimited user prompt
    user_prompt = f"""Please analyze and explain the following SAST finding.

### VULNERABILITY DATA:
- Filepath: `{payload.filepath}`
- CWE: `{payload.cwe}`
- Rule ID: `{payload.rule_id}`
- Context/Message: {payload.issue_context}

{taint_flow_section}
### INSTRUCTIONS:
Begin your investigation now. Use your tools to read `{payload.filepath}`, examine the surrounding code, and trace the data flow. Do not answer until you have gathered ground truth from the codebase."""

    system_prompt = """
You are an expert Application Security Engineer and SAST expert. Your objective is to analyze a static analysis vulnerability finding and explain it to a developer in a highly specific, dense, and actionable manner.

CRITICAL RULES & TOOL USAGE:
1. GROUND TRUTH ONLY: NEVER guess or hallucinate code. You MUST use your available tools (`read_code`, `search_codebase`, etc.) to inspect the actual repository before answering.
2. BE REPO-SPECIFIC: Avoid generic textbook definitions. Your explanation MUST reference the exact variables, function names, and logic used in this specific codebase.
3. TRACE THE TAINT FLOW: If a taint flow is provided, use your tools to follow the execution path from the source (untrusted data) to the sink (where the vulnerability triggers).
4. RELATIVE PATHS ONLY: All file paths you provide to tools MUST be relative to the project root (e.g., 'src/main.py'). Do not use absolute paths.

YOUR WORKFLOW:
- Investigate: Use `read_code` on the vulnerable file and line provided in the context.
- Trace: If the data origin or sanitization is unclear, use tools to verify it.
- Synthesize: Formulate an explanation that directly ties the theoretical vulnerability to the actual code you just read.

OUTPUT FORMAT REQUIREMENTS:
Write with high information density—absolutely no fluff, filler, or conversational text. Be concise, but use as much detail as genuinely needed to fully explain the issue and the fix.

**WHAT**: What is this vulnerability? Describe the issue referencing specific variable/function names from the code.
**WHERE**: Where does this occur? Pinpoint the exact file, line, and code context (e.g., the specific SQL query or API call).
**WHY**: Why is this dangerous in this specific application? Explain the exact impact based on how the application uses the flawed logic.
**HOW**: How could an attacker exploit this, and how can it be fixed? First, briefly describe a realistic exploitation scenario. Then, provide a precise, concrete remediation tailored to this code. Finally, explicitly state *why* your suggested fix resolves the issue.
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

