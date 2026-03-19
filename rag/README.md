# SAFE RAG Backend

FastAPI backend for retrieval-grounded explanation of software security findings in local code repositories.

It builds a FAISS index over the codebase, loads Semgrep rules, Security-Relevant Methods (SRMs), and MITRE CWE metadata, and exposes an agent endpoint that answers only from retrieved evidence.

## Features

- FAISS-based retrieval over a local codebase
- Syntax-aware chunking for code
- Semantic chunking for documentation
- Semgrep rule retrieval
- SRM lookup for security-relevant methods
- MITRE CWE lookup for weakness descriptions
- Agent endpoint for grounded explanations

## Setup

### Install

```bash
pip install fastapi uvicorn python-dotenv pydantic
pip install langchain langchain-community langchain-classic langchain-core
pip install langchain-openai langchain-experimental langchain-text-splitters
pip install faiss-cpu pypdf unstructured langfuse
```

### Environment

Create a `.env` file:

```env
AZURE_OPENAI_ENDPOINT=...
AZURE_OPENAI_API_KEY=...
AZURE_OPENAI_API_VERSION=...
AZURE_OPENAI_DEPLOYMENT=...
AZURE_OPENAI_EMBEDDING_MODEL=text-embedding-3-large-1
AZURE_OPENAI_EMBEDDING_DEPLOYMENT=...
AZURE_OPENAI_API_KEY=...
AZURE_OPENAI_EMBEDDING_PRICE_PER_1M=...
```

## Required local data

Place these files locally before startup:

```text
data/srms/java/srm-dataset-java.json
data/srms/java/srm-dataset-java-android-sinks.json
data/srms/java/srm-dataset-java-android-sources.json
data/mitre-cwe/cwec_v4.19.1.xml
```

Semgrep paths used by the backend:

```text
data/semgrep/community
data/semgrep/pro
```

## Run

```bash
python host_rag.py
```

Server runs on:

```text
http://localhost:8800
```

## Main endpoint

```text
POST /agent/explainer
```

Example:

```bash
curl -X POST "http://localhost:8800/agent/explainer" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Why is this method security relevant and what CWE is related?"
  }'
```

## Other endpoints

```text
POST /keyword-search
POST /semantic-search
POST /hybrid-search
POST /rebuild-index
GET  /health
POST /clone-and-rebuild
```

Example:
```bash
curl -X POST "http://localhost:8800/clone-and-rebuild" \
  -H "Content-Type: application/json" \
  -d '{
    "destination": "absolute path to destination",
    "remote_repo": "https://github.com/srctips/polyglot.git"
  }'
```

## Notes

- `/agent/explainer` is the main endpoint.
- Search endpoints are mainly for debugging and inspection.
- The agent can query codebase chunks, Semgrep rules, SRMs, and MITRE CWE entries.
- Answers should be grounded only in retrieved evidence.
