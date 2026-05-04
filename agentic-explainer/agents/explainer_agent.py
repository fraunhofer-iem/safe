import json
from langchain.agents import create_agent

from agents.tools import read_code, get_code_structure, search_codebase, find_files, list_files


def _format_taint_flow(taint_flow):
    """Render the trace as a numbered list so the agent can reference '**STEP 1.S**' markers."""
    if not taint_flow:
        return ""
    lines = ["### TAINT FLOW (Source to Sink):"]
    for step_index, step in enumerate(taint_flow, start=1):
        # Each step is a dict (free-form). Prefer file:line — message when available.
        file_part = step.get("file") or step.get("filePath") or ""
        line_part = step.get("line") or step.get("startLine")
        location = f"{file_part}:{line_part}" if file_part and line_part else (file_part or "(unknown location)")
        message = step.get("message") or step.get("description") or ""
        suffix = f" — {message}" if message else ""
        lines.append(f"  Step 1.{step_index}: {location}{suffix}")
    lines.append("")
    return "\n".join(lines)


def setup_explainer_agent(ctags_path: str, llm, payload, project_root: str):
    """
    Sets up the prompts and tools, and returns the compiled agent graph plus the
    initial state. The output is plain text using the SAFE plugin's marker format
    (TITLE / TLDR / WHAT / WHERE / WHY / HOW / DEEPDIVE / STEP T.S) so the plugin's
    existing response parser handles it without changes.
    """

    taint_flow_section = _format_taint_flow(payload.taint_flow)
    has_traces = bool(payload.taint_flow)

    snippet_section = ""
    if payload.snippet:
        snippet_section = f"### CODE SNIPPET:\n```\n{payload.snippet}\n```\n"

    line_range = ""
    if payload.start_line is not None:
        line_range = f"{payload.start_line}" + (f"-{payload.end_line}" if payload.end_line else "")

    user_prompt = f"""Please analyze and explain the following SAST finding.

### VULNERABILITY DATA:
- Filepath: `{payload.filepath}`
- Lines: {line_range or "(unknown)"}
- Severity: {payload.severity or "(unspecified)"}
- CWE: `{payload.cwe}`
- Rule ID: `{payload.rule_id}`
- Rule Description: {payload.rule_description}
- Context/Message: {payload.issue_context}

{snippet_section}{taint_flow_section}### INSTRUCTIONS:
Begin your investigation now. Use your tools to read `{payload.filepath}`, examine the surrounding code, and trace the data flow. Do not answer until you have gathered ground truth from the codebase. Then respond using the exact marker format described in the system prompt — no preamble, no closing remarks."""

    trace_guidance = (
        "When a data-flow trace is provided, refer to its steps explicitly in **WHERE** and **WHY** "
        '(e.g., "untrusted input enters at step 1 and reaches the sink at step N").'
        if has_traces else ""
    )

    step_instructions = (
        "\n\nThen, for every step in the data-flow trace above, give a 1–3 sentence explanation of what happens at that step "
        "(what value flows in, what is done with it, why it matters). Use this exact format:\n"
        "**STEP T.S**: <explanation>\n"
        "where T is the 1-based trace index (always 1 here, since we send one trace at a time) and S is the 1-based step "
        "index within that trace, in the order they appear in the trace data above.\n"
        "Example for a trace with three steps: **STEP 1.1**: ...   **STEP 1.2**: ...   **STEP 1.3**: ..."
        if has_traces else ""
    )

    step_response_hint = "\n**STEP 1.1**: <explanation>\n**STEP 1.2**: <explanation>\n..." if has_traces else ""

    system_prompt = f"""You are an expert Application Security Engineer and SAST expert. Your objective is to analyze a static analysis vulnerability finding and explain it to a developer in a highly specific, dense, and actionable manner.

CRITICAL RULES & TOOL USAGE:
1. GROUND TRUTH ONLY: NEVER guess or hallucinate code. You MUST use your available tools (`read_code`, `search_codebase`, `get_code_structure`, `find_files`, `list_files`) to inspect the actual repository before answering.
2. BE REPO-SPECIFIC: Avoid generic textbook definitions. Your explanation MUST reference the exact variables, function names, and logic used in this specific codebase.
3. TRACE THE TAINT FLOW: If a taint flow is provided, use your tools to follow the execution path from the source (untrusted data) to the sink (where the vulnerability triggers).
4. RELATIVE PATHS ONLY: All file paths you provide to tools MUST be relative to the project root (e.g., 'src/main.py'). Do not use absolute paths.

YOUR WORKFLOW:
- Investigate: Use `read_code` on the vulnerable file and line provided in the context.
- Trace: If the data origin or sanitization is unclear, use tools to verify it.
- Synthesize: Formulate an explanation that directly ties the theoretical vulnerability to the actual code you just read.

OUTPUT FORMAT REQUIREMENTS:
Respond using EXACTLY the marker format below. The plugin's parser keys off the literal `**MARKER**:` tokens — do not rename, omit, or reorder them. Write with high information density, no fluff or conversational text. Be concise but use as much detail as genuinely needed.

{trace_guidance}

**TITLE**: 8–14 words, sentence-case (no trailing period). Be specific: name the actual variable, parameter, or method from the code, the dangerous operation it reaches, and the enclosing class or method. Use active phrasing. Avoid the words "vulnerability", "issue", "potential", and the CWE id; do not quote the SAST rule message verbatim.
**TLDR**: One sentence (≤ 25 words), plain language, telling the reader what the bug is and what an attacker could do.
**WHAT**: What is this vulnerability? Describe the issue referencing specific variable/function names from the code. Max 2 sentences.
**WHERE**: Where does this occur? Pinpoint the exact file, line, and code context (e.g., the specific SQL query or API call). Max 2 sentences.
**WHY**: Why is this dangerous in this specific application? Explain the exact impact based on how the application uses the flawed logic. Max 2 sentences.
**HOW**: How could an attacker exploit this, and how can it be fixed? First, briefly describe a realistic exploitation scenario. Then, provide a precise, concrete remediation tailored to this code. Finally, explicitly state *why* your suggested fix resolves the issue. Max 2 sentences.
**DEEPDIVE**: A longer write-up for an experienced reader (4–8 sentences). Cover the underlying mechanism, why naive fixes don't work, edge cases, and any references (CVE numbers, OWASP Top 10 categories, RFC sections) that are relevant. Plain prose, no lists.{step_instructions}

Respond using exactly this format and nothing else:
**TITLE**: <title>
**TLDR**: <one-sentence summary>
**WHAT**: <explanation>
**WHERE**: <explanation>
**WHY**: <explanation>
**HOW**: <explanation>
**DEEPDIVE**: <longer write-up>{step_response_hint}
"""

    tools = [read_code, get_code_structure, search_codebase, find_files, list_files]

    # Compile the agent graph. We deliberately do NOT pass `response_format` here —
    # the plugin's parser expects free-form text with `**MARKER**:` separators, and
    # streaming tokens directly is more useful to the IDE than streaming JSON.
    agent_graph = create_agent(model=llm, tools=tools)

    initial_state = {
        "messages": [
            ("system", system_prompt),
            ("user", user_prompt),
        ]
    }

    return agent_graph, initial_state
