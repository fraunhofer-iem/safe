"""
SAST rule index for the SAFE agent.

The service is configured with a directory (or single file) of Semgrep-format YAML
rules via the `SAST_RULES_PATH` environment variable. On first use we walk that
path, parse every `.yml`/`.yaml` we find, and build an in-memory index keyed by
each rule's `id`. The agent's `lookup_sast_rule` tool consults this index so the
LLM can reason about the actual rule logic (patterns, message, CWE / OWASP
metadata, severity) when explaining a finding instead of guessing from the
rule id alone.
"""

import os
import threading
from pathlib import Path
from typing import Any, Dict, Iterable, Optional

import yaml


_index_lock = threading.Lock()
_cache: Optional[Dict[str, Any]] = None
_cached_root: Optional[str] = None


def get_rules_root() -> Optional[str]:
    """Returns the configured rules path, or `None` when unset."""
    raw = os.getenv("SAST_RULES_PATH")
    return raw.strip() if raw and raw.strip() else None


def _load_index(root: str) -> Dict[str, Any]:
    """
    Walk `root` and parse every Semgrep-format YAML in it. Each rule is stored
    under its `id`; if the same id appears in multiple files, the last one wins
    (matching Semgrep's own behaviour for shadowed rules). The original file
    path is preserved on `_source_file` for traceability.
    """
    index: Dict[str, Any] = {}
    p = Path(root).expanduser().resolve()
    if not p.exists():
        return index

    paths: Iterable[Path] = [p] if p.is_file() else sorted(p.rglob("*"))
    for f in paths:
        if not f.is_file():
            continue
        if f.suffix.lower() not in (".yml", ".yaml"):
            continue
        try:
            with f.open("r", encoding="utf-8") as fh:
                doc = yaml.safe_load(fh)
        except Exception:
            continue

        for rule in _extract_rules(doc):
            rid = rule.get("id")
            if not isinstance(rid, str) or not rid:
                continue
            entry = dict(rule)
            entry["_source_file"] = str(f)
            index[rid] = entry
    return index


def _extract_rules(doc: Any) -> Iterable[Dict[str, Any]]:
    """
    Yields rule dicts. Handles the two Semgrep-style layouts we see in the wild:
      - `{ rules: [ {id: ...}, ... ] }`            (canonical)
      - `[ {id: ...}, ... ]`                       (bare list, occasionally seen)
      - `{ id: ..., ... }`                         (single-rule file, not standard
                                                    but cheap to support)
    """
    if isinstance(doc, dict):
        rules = doc.get("rules")
        if isinstance(rules, list):
            for r in rules:
                if isinstance(r, dict):
                    yield r
            return
        if "id" in doc:
            yield doc
            return
    if isinstance(doc, list):
        for r in doc:
            if isinstance(r, dict):
                yield r


def get_index() -> Dict[str, Any]:
    """Lazily load and cache the rules index. Re-loads if `SAST_RULES_PATH` changes."""
    global _cache, _cached_root
    root = get_rules_root()
    if root is None:
        return {}
    with _index_lock:
        if _cache is None or _cached_root != root:
            _cache = _load_index(root)
            _cached_root = root
        return _cache


def find_rule(rule_id: str) -> Optional[Dict[str, Any]]:
    """
    Look up a rule by id. Tries an exact match first, then a tail-segment match
    so SARIF / Qodana long-dotted ids like
    `java.spring.security.spring-sqli-deepsemgrep.spring-sqli-deepsemgrep` find
    a Semgrep rule whose id is just `spring-sqli-deepsemgrep`. Returns `None` if
    nothing matches uniquely.
    """
    if not rule_id:
        return None
    index = get_index()
    if rule_id in index:
        return index[rule_id]

    last_segment = rule_id.split(".")[-1]
    candidates = [
        v for k, v in index.items()
        if k == last_segment
        or k.endswith("." + last_segment)
        or last_segment.endswith("." + k)
    ]
    return candidates[0] if len(candidates) == 1 else None


def index_size() -> int:
    return len(get_index())
