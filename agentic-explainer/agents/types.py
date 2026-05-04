from pydantic import BaseModel, Field
from typing import List, Dict, Any, Optional

class ExplainerRequest(BaseModel):
    rootpath: str = Field(..., description="Absolute path to the project root")
    filepath: str = Field(..., description="The path to the vulnerable file")
    issue_context: str = Field(..., description="The SAST finding message/context")

    cwe: str = "Unknown CWE"
    rule_id: str = "No rule_id provided"
    rule_description: str = "No rule description provided"
    severity: Optional[str] = None
    start_line: Optional[int] = None
    end_line: Optional[int] = None
    snippet: Optional[str] = None
    taint_flow: List[Dict[str, Any]] = []

    # ── Optional backend LLM override ─────────────────────────────────────────
    # When the plugin caller sets these, we instantiate a fresh LangChain LLM
    # for this request instead of using the service's env-var defaults. Lets the
    # user pick the model/key from the SAFE plugin's Settings UI.
    llm_provider: Optional[str] = Field(
        default=None,
        description="One of 'azure-openai', 'openai', 'anthropic', 'ollama'. None ⇒ use server default.",
    )
    llm_endpoint: Optional[str] = None
    llm_model: Optional[str] = None
    llm_api_key: Optional[str] = None
    llm_temperature: Optional[float] = None
