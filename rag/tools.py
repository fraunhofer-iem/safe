from typing import Optional
from pydantic import BaseModel, Field
from langchain.tools import tool
from langchain_community.retrievers import BM25Retriever
from langchain_classic.retrievers import EnsembleRetriever


def _search_srms_internal(
        store: dict,
        norm_fn,
        name: str | None = None,
        signature: str | None = None,
        top_k: int = 10,
) -> tuple[str, list[dict]]:
    if signature:
        hits = store["by_signature"].get(signature, [])
        if hits:
            return "exact_signature", hits[:top_k]

    if name:
        hits = store["by_name"].get(name, [])
        if hits:
            return "exact_name", hits[:top_k]

    if signature:
        hits = store["by_signature_norm"].get(norm_fn(signature), [])
        if hits:
            return "normalized_signature", hits[:top_k]

    if name:
        hits = store["by_name_norm"].get(norm_fn(name), [])
        if hits:
            return "normalized_name", hits[:top_k]

    query = norm_fn(signature or name)
    if not query:
        return "none", []

    loose = []
    seen = set()

    for entry in store["all_entries"]:
        sig = entry.get("signature", "") or ""
        nm = entry.get("name", "") or ""

        if query in norm_fn(sig) or query in norm_fn(nm):
            key = (
                entry.get("signature", ""),
                entry.get("name", ""),
                entry.get("artifacts", {}).get("compiled", ""),
            )
            if key not in seen:
                seen.add(key)
                loose.append(entry)
                if len(loose) >= top_k:
                    break

    return ("loose", loose) if loose else ("none", [])


def get_tools(vectorstore, all_chunks, semgrep_vectorstore, srm_store, cwe_store, norm_fn):
    """
    Returns search tools bound to the current codebase RAG, Semgrep rule index,
    and in-memory SRM dataset.
    """

    @tool
    def keyword_search(query: str, top_k: int = 5) -> str:
        """
        BM25-based keyword search (sparse retrieval) over the codebase.
        Use this for exact function names, class names, variable names,
        error messages, file names, or known terms like CWE IDs.
        """
        try:
            retriever = BM25Retriever.from_documents(all_chunks)
            retriever.k = top_k
            docs = retriever.invoke(query)

            output = []
            for i, doc in enumerate(docs):
                output.append(
                    f"[Result {i + 1}]\n"
                    f"File: {doc.metadata.get('file_path', 'unknown')}\n"
                    f"Content:\n{doc.page_content}"
                )
            return "\n---\n".join(output) if output else "No results found."

        except Exception as e:
            return f"Keyword search failed: {str(e)}"

    @tool
    def semantic_search(query: str, top_k: int = 5) -> str:
        """
        Dense vector similarity search using FAISS over the codebase.
        Use this for conceptually related code or documentation.
        """
        try:
            docs_with_scores = vectorstore.similarity_search_with_score(query, k=top_k)

            output = []
            for i, (doc, score) in enumerate(docs_with_scores):
                similarity = round(1 / (1 + float(score)), 3)
                output.append(
                    f"[Result {i + 1}] Similarity: {similarity}\n"
                    f"File: {doc.metadata.get('file_path', 'unknown')}\n"
                    f"Content:\n{doc.page_content}"
                )
            return "\n---\n".join(output) if output else "No results found."

        except Exception as e:
            return f"Semantic search failed: {str(e)}"

    @tool
    def hybrid_search(query: str, top_k: int = 5) -> str:
        """
        Hybrid search over the codebase using BM25 + FAISS.
        Use this when the query mixes exact identifiers with broader meaning.
        """
        try:
            bm25_retriever = BM25Retriever.from_documents(all_chunks)
            bm25_retriever.k = top_k * 2

            faiss_retriever = vectorstore.as_retriever(
                search_kwargs={"k": top_k * 2}
            )

            ensemble = EnsembleRetriever(
                retrievers=[bm25_retriever, faiss_retriever],
                weights=[0.4, 0.6]
            )

            docs = ensemble.invoke(query)

            output = []
            for i, doc in enumerate(docs[:top_k]):
                output.append(
                    f"[Result {i + 1}]\n"
                    f"File: {doc.metadata.get('file_path', 'unknown')}\n"
                    f"Content:\n{doc.page_content}"
                )
            return "\n---\n".join(output) if output else "No results found."

        except Exception as e:
            return f"Hybrid search failed: {str(e)}"

    @tool
    def semgrep_rule_search(query: str, top_k: int = 5, rule_id: str = "") -> str:
        """
        Search the Semgrep rules index.
        """
        try:
            output = []

            if rule_id:
                matches = []
                docstore = getattr(semgrep_vectorstore, "docstore", None)
                raw_docs = getattr(docstore, "_dict", {}) if docstore else {}

                for _, doc in raw_docs.items():
                    rule_ids = doc.metadata.get("rule_ids", [])
                    if isinstance(rule_ids, list) and rule_id in rule_ids:
                        matches.append(doc)

                if matches:
                    for i, doc in enumerate(matches[:top_k]):
                        output.append(
                            f"[Result {i + 1}] Exact Rule ID Match\n"
                            f"Source: {doc.metadata.get('source_type', 'unknown')}\n"
                            f"File: {doc.metadata.get('file_path', 'unknown')}\n"
                            f"Rule IDs: {doc.metadata.get('rule_ids', [])}\n"
                            f"Languages: {doc.metadata.get('languages', [])}\n"
                            f"Severities: {doc.metadata.get('severities', [])}\n"
                            f"Content:\n{doc.page_content}"
                        )
                    return "\n---\n".join(output)

            docs_with_scores = semgrep_vectorstore.similarity_search_with_score(query, k=top_k)

            for i, (doc, score) in enumerate(docs_with_scores):
                similarity = round(1 / (1 + float(score)), 3)
                output.append(
                    f"[Result {i + 1}] Similarity: {similarity}\n"
                    f"Source: {doc.metadata.get('source_type', 'unknown')}\n"
                    f"File: {doc.metadata.get('file_path', 'unknown')}\n"
                    f"Rule IDs: {doc.metadata.get('rule_ids', [])}\n"
                    f"Languages: {doc.metadata.get('languages', [])}\n"
                    f"Severities: {doc.metadata.get('severities', [])}\n"
                    f"Content:\n{doc.page_content}"
                )

            return "\n---\n".join(output) if output else "No Semgrep rule results found."

        except Exception as e:
            return f"Semgrep rule search failed: {str(e)}"

    class SearchSrmsInput(BaseModel):
        name: Optional[str] = Field(
            default=None,
            description="Fully qualified method name, e.g. org.pmw.tinylog.Logger.debug",
        )
        signature: Optional[str] = Field(
            default=None,
            description="Full method signature, e.g. void org.pmw.tinylog.Logger.debug(java.lang.String, java.lang.Object[])",
        )
        top_k: int = Field(
            default=10,
            ge=1,
            le=50,
            description="Maximum number of SRM matches to return",
        )

    @tool("search_srms", args_schema=SearchSrmsInput)
    def search_srms(
            name: str | None = None,
            signature: str | None = None,
            top_k: int = 10,
    ) -> dict:
        """
        Search the in-memory SRM dataset for potentially security-relevant methods.

        Tries exact signature, exact name, normalized exact matching, then loose matching.
        Returns the full SRM JSON objects.
        """
        if srm_store is None:
            return {
                "ok": False,
                "error": "SRM store not initialized",
                "match_type": "none",
                "count": 0,
                "results": [],
            }

        match_type, results = _search_srms_internal(
            store=srm_store,
            norm_fn=norm_fn,
            name=name,
            signature=signature,
            top_k=top_k,
        )

        return {
            "ok": True,
            "query": {
                "name": name,
                "signature": signature,
                "top_k": top_k,
            },
            "match_type": match_type,
            "count": len(results),
            "results": results,
        }

    class SearchCweInput(BaseModel):
        cwe_id: str = Field(
            ...,
            description="CWE identifier like CWE-79 or 79"
        )


    @tool("search_cwe", args_schema=SearchCweInput)
    def search_cwe(cwe_id: str) -> dict:
        """
        Look up a CWE by ID and return its full in-memory entry.
        """
        if cwe_store is None:
            return {
                "ok": False,
                "error": "CWE store not initialized",
                "results": [],
            }

        raw = cwe_id.strip()
        normalized = raw.upper()
        if not normalized.startswith("CWE-"):
            normalized = f"CWE-{normalized}"

        result = cwe_store.get(normalized)
        if result:
            return {
                "ok": True,
                "query": raw,
                "match_type": "exact",
                "count": 1,
                "results": [result],
            }

        return {
            "ok": True,
            "query": raw,
            "match_type": "none",
            "count": 0,
            "results": [],
        }

    return [
        semantic_search,
        hybrid_search,
        keyword_search,
        semgrep_rule_search,
        search_srms,
        search_cwe,
    ]
