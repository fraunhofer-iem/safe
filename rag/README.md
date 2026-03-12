# SAFE RAG Backend

A small FastAPI backend for retrieval-grounded questions about a source code repository.

It indexes a local project, stores embeddings in FAISS, and exposes an agent endpoint that answers questions based on retrieved code and documentation.

## How it works

1. A project directory is scanned recursively.
2. Code files are chunked with syntax-aware splitting.
3. Documentation-like files are chunked with semantic splitting.
4. The chunks are embedded and stored in FAISS.
5. The agent uses retrieval results to answer questions about the codebase.

## Setup

### Install

```bash
pip install fastapi uvicorn python-dotenv pydantic
pip install langchain langchain-community langchain-classic
pip install langchain-openai langchain-experimental langchain-text-splitters
pip install faiss-cpu pypdf unstructured langfuse
```

### Environment

Create a `.env` file in the project root:

```env
AZURE_OPENAI_ENDPOINT=...
AZURE_OPENAI_KEY=...
AZURE_OPENAI_API_VERSION=...
AZURE_OPENAI_DEPLOYMENT=...
```

## Run

```bash
python host_rag.py
```

The server starts locally on port `8800`.

## Main endpoint

The main endpoint is:

```text
POST /agent/explainer
```

Use it to ask questions about the indexed project.

### Example request

```bash
curl -X POST "http://localhost:8800/agent/explainer" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "How does this project parse SARIF results?"
  }'
```

### Example response

```json
{
  "answer": "..."
}
```

## Rebuilding the index

If you want to index a different project, rebuild the index first:

```bash
curl -X POST "http://localhost:8800/rebuild-index" \
  -H "Content-Type: application/json" \
  -d '{
    "root_dir": "/absolute/path/to/project"
  }'
```

## Notes

- `/agent/explainer` is the normal endpoint for usage.
- `/semantic-search`, `/keyword-search`, and `/hybrid-search` are mainly useful for manual inspection and debugging.
