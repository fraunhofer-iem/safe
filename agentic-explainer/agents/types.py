from pydantic import BaseModel, Field
from typing import List, Dict, Any, Optional

class ExplainerRequest(BaseModel):
    # rootpath is the only strictly required field
    rootpath: str = Field(..., description="Absolute path to the project root")

    # All other fields are optional and default to None
    filepath: Optional[str] = Field(default=None, description="The path to the vulnerable file")
    cwe: Optional[str] = None
    rule_id: Optional[str] = None
    rule_description: Optional[str] = None
    severity: Optional[str] = None
    message: Optional[str] = None
    start_line: Optional[int] = Field(default=None, alias="startLine")
    end_line: Optional[int] = Field(default=None, alias="endLine")
    snippet: Optional[str] = None

    # Defaults to an empty list instead of None, so you can safely iterate over it
    taint_flow: Optional[List[Dict[str, Any]]] = Field(default_factory=list)






class TraceStep(BaseModel):
    step_id: str = Field(description="The trace and step index, e.g., '1.1' or '1.2'")
    explanation: str = Field(description="A 1-3 sentence explanation of what happens at this step (what value flows in, what is done with it, why it matters).")



class ExplainerResponse(BaseModel):
    # Short TL;DR fields (Max 2 sentences)
    what: str = Field(description="Short summary: What is this vulnerability? (Max 2 sentences)")
    where: str = Field(description="Short summary: Where does this occur? (Max 2 sentences)")
    why: str = Field(description="Short summary: Why is it dangerous? (Max 2 sentences)")
    how: str = Field(description="Short summary: How can it be fixed? (Max 2 sentences)")

    # Enriched Deep-Dive fields (No length limit)
    what_detailed: str = Field(description="Detailed deep-dive into the vulnerability mechanics, context, and underlying theory.")
    where_detailed: str = Field(description="Detailed explanation of the location, tracing the architecture or data flow in-depth.")
    why_detailed: str = Field(description="Detailed explanation of attack vectors, edge cases, and specific exploitation scenarios.")
    how_detailed: str = Field(description="Detailed remediation guide, including step-by-step logic changes, defensive coding principles, or architectural shifts.")

    # Step-by-step Trace
    trace_steps: Optional[List[TraceStep]] = Field(default=[], description="List of step-by-step explanations for the taint flow.")

    title: str = Field(description="Title for this specific alert")

