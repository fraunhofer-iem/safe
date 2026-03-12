from langchain.tools import tool
from langchain_community.retrievers import BM25Retriever
from langchain_classic.retrievers import EnsembleRetriever


def get_tools(vectorstore, all_chunks):
    """
    Returns the three search tools bound to the current vectorstore and chunks.
    Called once at startup from chatbot.py.
    """

    @tool
    def keyword_search(query: str, top_k: int = 5) -> str:
        """
        BM25-based keyword search (sparse retrieval) over the codebase.
        Use this when searching for exact function names, variable names,
        specific error messages, or known technical terms like CWE IDs.
        """
        try:
            retriever = BM25Retriever.from_documents(all_chunks)
            retriever.k = top_k
            docs = retriever.invoke(query)

            output = []
            for i, doc in enumerate(docs):
                output.append(
                    f"[Result {i+1}]\n"
                    f"File: {doc.metadata.get('file_path', 'unknown')}\n"
                    f"Content:\n{doc.page_content}"
                )
            return "\n---\n".join(output) if output else "No results found."

        except Exception as e:
            return f"Keyword search failed: {str(e)}"

    @tool
    def semantic_search(query: str, top_k: int = 5) -> str:
        """
        Dense vector similarity search using FAISS embeddings over the codebase.
        Use this when looking for conceptually related code or documentation,
        such as understanding a vulnerability type, finding similar code patterns,
        or retrieving relevant security guidelines. Best for most queries.
        """
        try:
            docs_with_scores = vectorstore.similarity_search_with_score(query, k=top_k)

            output = []
            for i, (doc, score) in enumerate(docs_with_scores):
                similarity = round(1 / (1 + float(score)), 3)
                output.append(
                    f"[Result {i+1}] Similarity: {similarity}\n"
                    f"File: {doc.metadata.get('file_path', 'unknown')}\n"
                    f"Content:\n{doc.page_content}"
                )
            return "\n---\n".join(output) if output else "No results found."

        except Exception as e:
            return f"Semantic search failed: {str(e)}"

    @tool
    def hybrid_search(query: str, top_k: int = 5) -> str:
        """
        Hybrid search combining BM25 keyword search and FAISS semantic search
        using Reciprocal Rank Fusion. Use this as a fallback when semantic search
        returns low similarity scores, or when the query mixes exact terms
        (like a function name or CWE ID) with conceptual meaning.
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
                    f"[Result {i+1}]\n"
                    f"File: {doc.metadata.get('file_path', 'unknown')}\n"
                    f"Content:\n{doc.page_content}"
                )
            return "\n---\n".join(output) if output else "No results found."

        except Exception as e:
            return f"Hybrid search failed: {str(e)}"

    return [semantic_search, hybrid_search, keyword_search]
