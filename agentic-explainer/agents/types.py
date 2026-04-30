from pydantic import BaseModel, Field
from typing import List, Dict, Any, Optional

class ExplainerRequest(BaseModel):
    rootpath: str = Field(..., description="Absolute path to the project root")
    filepath: str = Field(..., description="The path to the vulnerable file")
    issue_context: str = Field(..., description="The SAST finding message/context")

    # These fields are optional with default values
    cwe: str = "Unknown CWE"
    rule_id: str = "No rule_id provided"
    rule_description: str = "No rule description provided"
    taint_flow: List[Dict[str, Any]] = []


class ExplainerResponse(BaseModel):
    """Explainer Response"""
    what: str = Field(description="WHAT: What is this vulnerability? Describe the type and nature of the security issue.")
    where: str = Field(description="WHERE: Where does this vulnerability occur? Reference the file, line, and code context if available.")
    why: str = Field(description="WHY: Why is this dangerous? Explain the potential impact or risk.")
    how: str = Field(description="HOW: How can this be fixed? Provide a concise remediation approach.")




