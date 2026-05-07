import json
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Dict, List

from langchain.tools import ToolRuntime
from langchain.tools import tool




def resolve_safe_path(requested_path: str, project_root_raw: str) -> Path:
    """
    Takes any path (relative or absolute) and returns a resolved absolute Path.
    Raises a PermissionError if the path attempts to escape the project root.
    """
    if not project_root_raw:
        raise ValueError("Project root is missing from tool configuration.")

    root = Path(project_root_raw).resolve()
    candidate = Path(requested_path)

    # If the LLM passed a relative path, append it to the project root
    if not candidate.is_absolute():
        candidate = root / candidate

    candidate = candidate.resolve()

    # SECURITY CHECK: Ensure the resolved path is strictly inside the project root
    # (Python 3.9+ supports .is_relative_to())
    if not candidate.is_relative_to(root):
        raise PermissionError(f"Access Denied: Path '{requested_path}' is outside the project root.")

    if candidate.suffix.lower() == '.sarif':
        raise PermissionError(f"Access Denied: You are not allowed to interact with .sarif files.")

    return candidate



@tool
def get_code_structure(file_path: str, runtime: ToolRuntime) -> str:
    """
    Retrieves the list of functions, classes, and methods for a specific file from the pre-computed index.
    Returns their names and exact line ranges (start-end).
    """
    ctags_path = runtime.config.get("metadata", {}).get("ctags_path")
    try:
        with open(ctags_path, "r") as f:
            _WORKER_DB_CACHE = json.load(f)
        # print(f"[Debug] Loaded C-Tags with {len(_WORKER_DB_CACHE)} entries.")
    except FileNotFoundError:
        return f"Error: {ctags_path} not found."

    # print(f"_WORKER_DB_CACHE: {_WORKER_DB_CACHE}")

    # 1. Try Exact Match
    entries = _WORKER_DB_CACHE.get(file_path)

    # print(f"entries: {entries}")
    # 2. If not found, try Fuzzy/Relative Match
    # The agent might give "BenchmarkTest01344.java" but DB has "/full/path/to/BenchmarkTest01344.java"
    if not entries:
        # Look for keys that END with the requested path
        matching_keys = [k for k in _WORKER_DB_CACHE.keys() if k.endswith(file_path) or file_path in k]

        if len(matching_keys) == 1:
            # Perfect, we found the unique file
            entries = _WORKER_DB_CACHE[matching_keys[0]]
        elif len(matching_keys) > 1:
            # Ambiguous
            return f"Error: Ambiguous file path. Did you mean:\n" + "\n".join(matching_keys[:5])
        else:
            return f"Error: No structure found for {file_path} in index."

    # 3. Format the Output
    results = []
    for tag in entries:
        kind = tag['kind'].capitalize()
        name = tag['name']
        signature = tag.get('signature', "")
        start = tag['start']
        end = tag.get('end', '?')  # Handle missing end lines gracefully

        results.append(f"[{kind}] {name}{signature} : Lines {start}-{end}")

    # Sort by line number for readability
    results.sort(key=lambda x: int(x.split("Lines ")[1].split("-")[0]))

    return "\n".join(results)


@tool
def read_code(file_path: str, start_line: int | None = None, end_line: int | None = None, runtime: ToolRuntime = None) -> str:
    """
    Read source code lines from a file.
    You should provide file_path relative to the project root.
    """

    metadata = (runtime.config or {}).get("metadata", {}) if runtime else {}
    project_root = metadata.get("project_root")

    try:
        safe_path = resolve_safe_path(file_path, project_root)
    except Exception as e:
        return f"Error: {str(e)}"

    try:
        lines = safe_path.read_text(encoding="utf-8").splitlines()

        # Ensure indices are within valid range
        total_lines = len(lines)
        start = max(1, start_line or 1)
        end = min(total_lines, end_line or total_lines)

        # Return the selected range
        snippet = "\n".join(lines[start - 1:end])
        # print(f"reading code: {file_path}")
        return snippet

    except FileNotFoundError:
        return f"Error: File not found -> {file_path}"
    except Exception as e:
        return f"Error reading source ({type(e).__name__}): {e}"


@tool
def search_codebase(
        pattern: str,
        file_list: List[str] | None = None,
        case_sensitive: bool = False,
        runtime: ToolRuntime = None,
) -> str:
    """
    Search for a pattern across the codebase, returning relative paths.
    """
    metadata = (runtime.config or {}).get("metadata", {}) if runtime else {}
    project_root_raw = metadata.get("project_root")

    if not project_root_raw:
        return "Error: project_root missing from ToolRuntime metadata."

    try:
        project_root = Path(project_root_raw).resolve()
    except Exception as e:
        return f"Error: invalid project_root '{project_root_raw}': {e}"

    safe_targets: list[str] = []

    if file_list:
        for p in file_list:
            try:
                safe_path = resolve_safe_path(p, project_root_raw)
                if safe_path.exists():
                    safe_targets.append(str(safe_path))
            except Exception:
                continue

        if not safe_targets:
            return "Error: all provided file_list paths were outside project_root, invalid, or did not exist."
    else:
        safe_targets = [str(project_root)]

    cmd = ["rg", "--json", "-n", "-F"]
    if not case_sensitive:
        cmd.append("-i")
    cmd.extend(["--", pattern])
    cmd.extend(safe_targets)

    try:
        result = subprocess.run(
            cmd,
            cwd=str(project_root),
            capture_output=True,
            text=True,
            timeout=20,
        )

        matches = []
        for line in result.stdout.splitlines():
            if not line.strip(): continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue

            if entry.get("type") != "match":
                continue

            data = entry["data"]
            # Convert the absolute path back to a relative path for the LLM
            absolute_path = Path(data["path"]["text"])
            try:
                rel_path = str(absolute_path.relative_to(project_root))
            except ValueError:
                rel_path = str(absolute_path) # Fallback if it somehow fails

            line_no = data["line_number"]
            full_snippet = data["lines"]["text"].strip()

            search_in = full_snippet if case_sensitive else full_snippet.lower()
            search_for = pattern if case_sensitive else pattern.lower()
            snippet_start = search_in.find(search_for)
            if snippet_start == -1: snippet_start = 0

            snippet = full_snippet[max(0, snippet_start - 40):snippet_start + 60][:100]

            matches.append(f"{rel_path}, line:{line_no} | snippet:{snippet}")
            if len(matches) >= 50: break

        if not matches:
            return f"No matches found for '{pattern}'."

        return f"Found {len(matches)} match(es) for '{pattern}':\n" + "\n".join(matches)

    except subprocess.TimeoutExpired:
        return "Error: ripgrep search timed out (>20s)."
    except FileNotFoundError:
        return "Error: ripgrep (rg) is not installed or not in PATH."
    except Exception as e:
        return f"Error during search: {type(e).__name__}: {e}"





@tool
def get_code_structure_deep_agent(file_path: str, ctags_path: str) -> str:
    """
    Retrieves the list of functions, classes, and methods for a specific file from the pre-computed index.
    Returns their names and exact line ranges (start-end).
    """
    try:
        with open(ctags_path, "r") as f:
            _WORKER_DB_CACHE = json.load(f)
        # print(f"[Debug] Loaded C-Tags with {len(_WORKER_DB_CACHE)} entries.")
    except FileNotFoundError:
        return f"Error: {ctags_path} not found."

    # print(f"_WORKER_DB_CACHE: {_WORKER_DB_CACHE}")

    # 1. Try Exact Match
    entries = _WORKER_DB_CACHE.get(file_path)

    # print(f"entries: {entries}")
    # 2. If not found, try Fuzzy/Relative Match
    # The agent might give "BenchmarkTest01344.java" but DB has "/full/path/to/BenchmarkTest01344.java"
    if not entries:
        # Look for keys that END with the requested path
        matching_keys = [k for k in _WORKER_DB_CACHE.keys() if k.endswith(file_path) or file_path in k]

        if len(matching_keys) == 1:
            # Perfect, we found the unique file
            entries = _WORKER_DB_CACHE[matching_keys[0]]
        elif len(matching_keys) > 1:
            # Ambiguous
            return f"Error: Ambiguous file path. Did you mean:\n" + "\n".join(matching_keys[:5])
        else:
            return f"Error: No structure found for {file_path} in index."

    # 3. Format the Output
    results = []
    for tag in entries:
        kind = tag['kind'].capitalize()
        name = tag['name']
        signature = tag.get('signature', "")
        start = tag['start']
        end = tag.get('end', '?')  # Handle missing end lines gracefully

        results.append(f"[{kind}] {name}{signature} : Lines {start}-{end}")

    # Sort by line number for readability
    results.sort(key=lambda x: int(x.split("Lines ")[1].split("-")[0]))

    return "\n".join(results)




@tool
def list_files(path: str = ".", max_entries: int = 200, runtime: ToolRuntime = None) -> str:
    """
    List files in a directory using 'ls -la' with truncated output.
    """
    metadata = (runtime.config or {}).get("metadata", {}) if runtime else {}
    project_root_raw = metadata.get("project_root")

    if not project_root_raw:
        return "Error: project_root missing from ToolRuntime metadata."

    try:
        candidate = resolve_safe_path(path, project_root_raw)

        if not candidate.exists():
            return f"Error: path does not exist -> {path}"
        if not candidate.is_dir():
            return f"Error: path is not a directory -> {path}"

        result = subprocess.run(
            ["ls", "-la", str(candidate)],
            cwd=str(Path(project_root_raw).resolve()),
            capture_output=True,
            text=True,
            timeout=10,
        )

        if result.returncode != 0:
            return f"Error running ls: {result.stderr.strip()}"

        lines = result.stdout.splitlines()
        trimmed = lines[:max_entries]
        output = "\n".join(trimmed)

        if len(lines) > max_entries:
            output += f"\n... [truncated, showing first {max_entries} lines]"
        if len(output) > 6000:
            output = output[:6000] + "\n... [truncated by size]"

        return output

    except PermissionError as e:
        return str(e)
    except Exception as e:
        return f"Error listing files ({type(e).__name__}): {e}"



@tool
def find_files(name_pattern: str = "", path: str = ".", runtime: ToolRuntime = None) -> str:
    """
    Find files by filename/path substring under the project root.
    """
    metadata = (runtime.config or {}).get("metadata", {}) if runtime else {}
    project_root_raw = metadata.get("project_root")

    if not project_root_raw:
        return "Error: project_root missing from ToolRuntime metadata."

    project_root = Path(project_root_raw).resolve()

    try:
        candidate = resolve_safe_path(path, project_root_raw)

        if not candidate.exists():
            return f"Error: path does not exist -> {path}"
        if not candidate.is_dir():
            return f"Error: path is not a directory -> {path}"

        pattern = (name_pattern or "").lower()
        matches = []

        for p in candidate.rglob("*"):
            if not p.is_file():
                continue

            # Ensure the output is relative to the project root
            rel = str(p.relative_to(project_root))
            name = p.name.lower()
            rel_lower = rel.lower()

            if not pattern or pattern in name or pattern in rel_lower:
                matches.append(rel)

            if len(matches) >= 200:
                break

        if not matches:
            return f"No files found matching '{name_pattern}'."

        output = "\n".join(matches[:200])
        if len(matches) > 200:
            output += "\n... [truncated]"
        return output

    except PermissionError as e:
        return str(e)
    except Exception as e:
        return f"Error finding files ({type(e).__name__}): {e}"

@tool
def lookup_sast_rule(rule_id: str, runtime: ToolRuntime = None) -> str:
    """
    Look up the SAST rule definition for `rule_id` in the indexed rules
    directory (configured by `SAST_RULES_PATH` on the service). Returns the
    rule's content as YAML — id, message, patterns, severity, metadata
    (CWE / OWASP). Use this whenever the finding has a rule_id so your
    explanation reflects what the rule actually checks rather than guessing
    from the id.

    Falls back to a tail-segment match for long-dotted ids
    (`java.spring.security.spring-sqli-deepsemgrep.spring-sqli-deepsemgrep`
    finds a Semgrep rule whose id is just `spring-sqli-deepsemgrep`).
    """
    import yaml

    from agents.rules import find_rule, get_rules_root, index_size

    if not get_rules_root():
        return (
            "Error: SAST rules directory is not configured. "
            "Set SAST_RULES_PATH on the service to a directory of Semgrep YAMLs."
        )
    rule = find_rule(rule_id)
    if rule is None:
        return (
            f"Error: rule {rule_id!r} not found in the loaded SAST rules "
            f"({index_size()} rules indexed)."
        )
    return yaml.safe_dump(rule, sort_keys=False, default_flow_style=False)
