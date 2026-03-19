import os
import re
import hashlib
import json
from typing import List, Dict, Any, Optional
from pathlib import Path
import heapq
import xml.etree.ElementTree as ET

from collections import Counter, defaultdict

from fastapi import FastAPI, HTTPException, Request
from git import Repo
import shutil
from pydantic import BaseModel
from contextlib import asynccontextmanager

from langchain_community.document_loaders import (
    DirectoryLoader,
    TextLoader,
    PyPDFLoader,
    UnstructuredMarkdownLoader
)
from langchain_text_splitters import RecursiveCharacterTextSplitter, Language
from langchain_experimental.text_splitter import SemanticChunker
from langchain_community.vectorstores import FAISS
from dotenv import load_dotenv
from langchain_openai import OpenAIEmbeddings
from langchain_community.retrievers import BM25Retriever
from langchain_classic.retrievers import EnsembleRetriever
from langchain_core.documents import Document
from langfuse.langchain import CallbackHandler
from langchain_openai import AzureOpenAIEmbeddings

from safeRetrieverAgent import create_agent_executor, SourceItem

from ingest_semgrep_rules import build_semgrep_index
from cost_tracing import TracedAzureOpenAIEmbeddings

# ============================================
# Configuration
# ============================================

load_dotenv()
langfuse_handler = CallbackHandler()
CODEBASE_PATH = "./codebase"  # Path to your code and docs
FAISS_INDEX_PATH = "./faiss_index_codebase"
CACHE_FILE = "./codebase_hash.json"
EMBEDDING_MODEL = "text-embedding-3-large"  # Later: "voyage-code-3"

# Semgrep
COMMUNITY_REPO_URL = "https://github.com/semgrep/semgrep-rules.git"
COMMUNITY_DIR = Path("./data/semgrep/community")
PRO_DIR = Path("./data/semgrep/pro")  # <-- point this to your local pro-rules dir
INDEX_DIR = Path("./faiss_index_semgrep")
MANIFEST_PATH = INDEX_DIR / "rules_manifest.json"

SRM_FILES = [
    "data/srms/java/srm-dataset-java.json",
    "data/srms/java/srm-dataset-java-android-sinks.json",
    "data/srms/java/srm-dataset-java-android-sources.json",
]

CWE_XML_PATH = "data/mitre-cwe/cwec_v4.19.1.xml"

# File type blacklist (binary files, images, etc.)
BLACKLIST_EXTENSIONS = {
    # Images
    '.png', '.jpg', '.jpeg', '.gif', '.bmp', '.svg', '.ico', '.webp',
    # Binaries
    '.exe', '.dll', '.so', '.dylib', '.bin', '.class', '.jar',
    # Archives
    '.zip', '.tar', '.gz', '.rar', '.7z',
    # Media
    '.mp4', '.mp3', '.avi', '.mov', '.wav',
    # Other
    '.pyc', '.pyo', '.cache', '.log', '.lock',
    '.git', '.gitignore', '.DS_Store',
    '.html'
}

# Code file extensions (use syntax-aware chunking)
CODE_EXTENSIONS = {
    '.py': Language.PYTHON,
    '.java': Language.JAVA,
    '.c': Language.C,
    '.cpp': Language.CPP,
    '.cs': Language.CSHARP,
    '.go': Language.GO,
    '.rs': Language.RUST,
    '.js': Language.JS,
    '.ts': Language.TS,
    '.php': Language.PHP,
    '.rb': Language.RUBY,
    '.kt': Language.KOTLIN,
    '.scala': Language.SCALA,
    '.swift': Language.SWIFT,
}

# Documentation file extensions (use semantic chunking)
DOC_EXTENSIONS = {'.md', '.txt', '.rst', '.pdf', '.html', '.tex', '.latex'}


# ============================================
# Codebase Hashing for Cache Validation
# ============================================

def compute_codebase_hash(base_path: str) -> str:
    """
    Compute a hash of all files in the codebase.
    If hash matches cached hash, skip re-indexing.
    """
    hash_obj = hashlib.sha256()

    # Walk through all files
    for root, dirs, files in os.walk(base_path):
        # Skip hidden directories
        dirs[:] = [d for d in dirs if not d.startswith('.')]

        for file in sorted(files):  # Sort for consistent hashing
            file_path = Path(root) / file
            ext = file_path.suffix.lower()

            # Skip blacklisted files
            if ext in BLACKLIST_EXTENSIONS:
                continue

            try:
                # Hash file path and modification time
                hash_obj.update(str(file_path).encode())
                hash_obj.update(str(file_path.stat().st_mtime).encode())
            except Exception:
                continue

    return hash_obj.hexdigest()


def is_index_valid(root_dir: str) -> bool:
    if not os.path.exists(FAISS_INDEX_PATH) or not os.path.exists(CACHE_FILE):
        return False

    try:
        with open(CACHE_FILE, "r") as f:
            cache_data = json.load(f)

        current_hash = compute_codebase_hash(root_dir)
        return (
                cache_data.get("codebase_hash") == current_hash
                and cache_data.get("root_dir") == root_dir
        )
    except Exception:
        return False


def save_cache(codebase_hash: str, root_dir: str):
    with open(CACHE_FILE, "w") as f:
        json.dump({
            "codebase_hash": codebase_hash,
            "root_dir": root_dir
        }, f)


# ============================================
# Document Loading (All File Types)
# ============================================

def load_all_documents(base_path: str) -> List[Document]:
    """
    Load ALL documents from codebase except blacklisted file types.
    Handles any arbitrary project structure.
    """
    all_docs = []

    for root, dirs, files in os.walk(base_path):
        # Skip hidden directories (.git, .venv, etc.)
        dirs[:] = [d for d in dirs if not d.startswith('.')]

        for file in files:
            file_path = Path(root) / file
            ext = file_path.suffix.lower()

            # Skip blacklisted extensions
            if ext in BLACKLIST_EXTENSIONS:
                continue

            # Skip hidden files
            if file.startswith('.'):
                continue

            try:
                # Handle PDFs specially
                if ext == '.pdf':
                    loader = PyPDFLoader(str(file_path))
                    docs = loader.load()
                # Handle Markdown specially
                elif ext == '.md':
                    loader = UnstructuredMarkdownLoader(str(file_path))
                    docs = loader.load()
                # Everything else as text (code, txt, etc.)
                else:
                    loader = TextLoader(str(file_path), encoding='utf-8')
                    docs = loader.load()

                # Add file extension to metadata for routing
                for doc in docs:
                    doc.metadata['file_extension'] = ext
                    doc.metadata['file_path'] = str(file_path)

                all_docs.extend(docs)

            except Exception as e:
                print(f"Failed to load {file_path}: {e}")
                continue

    return all_docs


# ============================================
# Smart Chunking Based on File Type
# ============================================

def chunk_documents(docs: List[Document], embeddings) -> List[Document]:
    """
    Apply appropriate chunking strategy based on file type:
    - Code files (.py, .java, .c, etc.): Syntax-aware chunking
    - Documentation (.md, .pdf, .txt): Semantic chunking
    - Everything else: Fallback recursive chunking
    """
    chunked_docs = []

    syntax_chunker, sematic_chunker, default_chunker = 0, 0, 0
    syntax_chunker_files, semantic_chunker_files, default_chunker_files = [], [], []

    file_chunk_stats = []

    for idx, doc in enumerate(docs, start=1):
        ext = doc.metadata.get("file_extension", "").lower()
        source = doc.metadata.get("source", "<unknown>")
        text_len = len(doc.page_content or "")

        if idx % 100 == 0:
            print(f"Chunking {idx}/{len(docs)}")

        try:
            # 1. Code files: Syntax-aware chunking
            if ext in CODE_EXTENSIONS:
                language = CODE_EXTENSIONS[ext]
                splitter = RecursiveCharacterTextSplitter.from_language(
                    language=language,
                    chunk_size=1500,
                    chunk_overlap=100
                )
                chunks = splitter.split_documents([doc])
                chunked_docs.extend(chunks)
                syntax_chunker += 1
                syntax_chunker_files.append(ext)
                chunker_type = "syntax"

            # 2. Documentation files: Semantic chunking
            elif ext in DOC_EXTENSIONS:
                splitter = SemanticChunker(embeddings)
                chunks = splitter.split_documents([doc])
                chunked_docs.extend(chunks)
                sematic_chunker += 1
                semantic_chunker_files.append(ext)
                chunker_type = "semantic"

            # 3. Unknown file types: Fallback to simple chunking
            else:
                splitter = RecursiveCharacterTextSplitter(
                    chunk_size=1000,
                    chunk_overlap=80
                )
                chunks = splitter.split_documents([doc])
                chunked_docs.extend(chunks)
                default_chunker += 1
                default_chunker_files.append(ext)
                chunker_type = "default"

            chunk_count = len(chunks)
            total_chunk_chars = sum(len(c.page_content or "") for c in chunks)
            avg_chunk_chars = total_chunk_chars / chunk_count if chunk_count else 0

            file_chunk_stats.append({
                "source": source,
                "ext": ext,
                "chunker": chunker_type,
                "text_len": text_len,
                "chunk_count": chunk_count,
                "avg_chunk_chars": avg_chunk_chars,
            })

            if chunk_count > 100:
                print(
                    f"[HIGH CHUNK COUNT] {source} | ext={ext or '<noext>'} "
                    f"| chunker={chunker_type} | chars={text_len} "
                    f"| chunks={chunk_count} | avg_chunk_chars={avg_chunk_chars:.1f}"
                )

        except Exception as e:
            print(f"[CHUNK ERROR] {source} | ext={ext or '<noext>'} | error={e}")

    print("\nTop 30 files by chunk count:")
    for stat in heapq.nlargest(30, file_chunk_stats, key=lambda x: x["chunk_count"]):
        print(
            f"{stat['chunk_count']:>6} chunks | {stat['text_len']:>7} chars "
            f"| avg={stat['avg_chunk_chars']:>6.1f} | {stat['chunker']:<8} "
            f"| {stat['ext'] or '<noext>':<8} | {stat['source']}"
        )

    print("\nTop 10 extensions by total chunks:")
    ext_chunk_counter = Counter()
    for stat in file_chunk_stats:
        ext_chunk_counter[stat["ext"] or "<noext>"] += stat["chunk_count"]

    for ext, count in ext_chunk_counter.most_common(10):
        print(f"{ext:<10} -> {count} chunks")

    print("\nTop 10 chunker types by total chunks:")
    chunker_counter = Counter()
    for stat in file_chunk_stats:
        chunker_counter[stat["chunker"]] += stat["chunk_count"]

    for chunker, count in chunker_counter.most_common():
        print(f"{chunker:<10} -> {count} chunks")

    print(f"used syntax chunker on {syntax_chunker} | extensions: {set(syntax_chunker_files)}")
    print(f"used sematic chunker on {sematic_chunker} | extensions: {set(semantic_chunker_files)}")
    print(f"used default chunker on {default_chunker} | extensions: {set(default_chunker_files)}")
    return chunked_docs


def get_azure_embeddings():
    price_raw = os.getenv("AZURE_OPENAI_EMBEDDING_PRICE_PER_1M", "").strip()
    price = float(price_raw) if price_raw else None

    return TracedAzureOpenAIEmbeddings(
        model=os.getenv("AZURE_OPENAI_EMBEDDING_MODEL", "text-embedding-3-large"),
        azure_endpoint=os.getenv("AZURE_OPENAI_ENDPOINT"),
        api_key=os.getenv("AZURE_OPENAI_API_KEY"),
        api_version=os.getenv("AZURE_OPENAI_API_VERSION"),
        azure_deployment=os.getenv("AZURE_OPENAI_EMBEDDING_DEPLOYMENT"),
        price_per_1m_tokens=price,
    )
def get_openai_embeddings():
    return OpenAIEmbeddings(model=EMBEDDING_MODEL)


# ============================================
# RAG Initialization with Caching
# ============================================

def initialize_rag_system(root_dir: str):
    if is_index_valid(root_dir):
        print("Valid FAISS index found. Loading from cache...")
        # embeddings = get_azure_embeddings()
        embeddings = get_openai_embeddings()
        vectorstore = FAISS.load_local(
            FAISS_INDEX_PATH,
            embeddings,
            allow_dangerous_deserialization=True
        )

        with open(f"{FAISS_INDEX_PATH}/chunks.json", "r") as f:
            chunks_data = json.load(f)
            all_chunks = [
                Document(page_content=c["content"], metadata=c["metadata"])
                for c in chunks_data
            ]

        print(f"Loaded {vectorstore.index.ntotal} vectors and {len(all_chunks)} chunks")
        return vectorstore, all_chunks, embeddings

    print("No valid cache found. Building new index...")
    print(f"Scanning codebase at: {root_dir}")

    docs = load_all_documents(root_dir)
    print(f"Loaded {len(docs)} documents")

    # embeddings = get_azure_embeddings()
    embeddings = get_openai_embeddings()
    chunked_docs = chunk_documents(docs, embeddings)
    print(f"Created {len(chunked_docs)} chunks")

    if len(chunked_docs) == 0:
        raise ValueError("No chunks created! Check your codebase path and file types.")

    vectorstore = FAISS.from_documents(chunked_docs, embeddings)
    vectorstore.save_local(FAISS_INDEX_PATH)

    chunks_data = [
        {"content": c.page_content, "metadata": c.metadata}
        for c in chunked_docs
    ]
    with open(f"{FAISS_INDEX_PATH}/chunks.json", "w") as f:
        json.dump(chunks_data, f)

    save_cache(compute_codebase_hash(root_dir), root_dir)

    print("RAG system initialized and cached")
    return vectorstore, chunked_docs, embeddings


######################
# #SRMS
######################
def _norm(s: str | None) -> str:
    if not s:
        return ""
    return re.sub(r"\s+", " ", s).strip().lower()


def _load_srm_entries(paths: list[str]) -> dict[str, Any]:
    all_entries: list[dict] = []
    by_signature: dict[str, list[dict]] = defaultdict(list)
    by_name: dict[str, list[dict]] = defaultdict(list)
    by_signature_norm: dict[str, list[dict]] = defaultdict(list)
    by_name_norm: dict[str, list[dict]] = defaultdict(list)

    for path in paths:
        with open(path, "r", encoding="utf-8") as f:
            payload = json.load(f)

        if isinstance(payload, dict):
            methods = payload.get("methods", [])
        elif isinstance(payload, list):
            methods = payload
        else:
            print(f"Skipping unsupported SRM file format: {path}")
            continue

        for entry in methods:
            if not isinstance(entry, dict):
                continue

            all_entries.append(entry)

            signature = (entry.get("signature", "") or "").strip()
            name = (entry.get("name", "") or "").strip()

            if signature:
                by_signature[signature].append(entry)
                by_signature_norm[_norm(signature)].append(entry)

            if name:
                by_name[name].append(entry)
                by_name_norm[_norm(name)].append(entry)

    return {
        "all_entries": all_entries,
        "by_signature": by_signature,
        "by_name": by_name,
        "by_signature_norm": by_signature_norm,
        "by_name_norm": by_name_norm,
    }


##################
# CWE MITRE
##################

def _xml_local_name(tag: str) -> str:
    return tag.split("}", 1)[-1] if "}" in tag else tag


def _find_child_text(elem, child_name: str, default: str = "") -> str:
    for child in elem:
        if _xml_local_name(child.tag) == child_name:
            text = "".join(child.itertext()).strip() if child is not None else ""
            return text or default
    return default


def _load_cwe_entries(xml_path: str) -> dict[str, dict]:
    tree = ET.parse(xml_path)
    root = tree.getroot()

    cwe_store: dict[str, dict] = {}

    for elem in root.iter():
        if _xml_local_name(elem.tag) != "Weakness":
            continue

        cwe_id = elem.attrib.get("ID", "").strip()
        if not cwe_id:
            continue

        key = f"CWE-{cwe_id}"
        cwe_store[key] = {
            "id": key,
            "numeric_id": cwe_id,
            "name": elem.attrib.get("Name", "").strip(),
            "abstraction": elem.attrib.get("Abstraction", "").strip(),
            "status": elem.attrib.get("Status", "").strip(),
            "description": _find_child_text(elem, "Description", ""),
            "extended_description": _find_child_text(elem, "Extended_Description", ""),
        }

    return cwe_store


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.srm_store = _load_srm_entries(SRM_FILES)
    app.state.cwe_store = _load_cwe_entries(CWE_XML_PATH)

    vectorstore, all_chunks, embeddings = initialize_rag_system(CODEBASE_PATH)

    app.state.semgrep_vectorstore = build_semgrep_index(
        index_dir=INDEX_DIR,
        pro_dir=PRO_DIR,
        community_dir=COMMUNITY_DIR,
        community_repo_url=COMMUNITY_REPO_URL,
        manifest_path=MANIFEST_PATH,
        embedding_model=EMBEDDING_MODEL,
    )

    app.state.vectorstore = vectorstore
    app.state.all_chunks = all_chunks
    app.state.embeddings = embeddings

    app.state.agent = create_agent_executor(
        vectorstore=app.state.vectorstore,
        all_chunks=app.state.all_chunks,
        semgrep_vector_store=app.state.semgrep_vectorstore,
        srm_store=app.state.srm_store,
        cwe_store=app.state.cwe_store,
        norm_fn=_norm,
    )

    yield


# ============================================
# FastAPI Application
# ============================================

app = FastAPI(title="SAFE RAG Backend", version="1.0.0", lifespan=lifespan)

# Initialize on startup moved to lifespan
# vectorstore, all_chunks, embeddings = initialize_rag_system(CODEBASE_PATH)
#
# semgrep_vectorstore = build_semgrep_index(index_dir=INDEX_DIR, pro_dir=PRO_DIR, community_dir=COMMUNITY_DIR,
#                                           community_repo_url=COMMUNITY_REPO_URL, manifest_path=MANIFEST_PATH,
#                                           embedding_model=EMBEDDING_MODEL)
#
# agent = create_agent_executor(vectorstore, all_chunks, semgrep_vectorstore, srm_store=app.state.srm_store,
#                               cwe_store=app.state.cwe_store, norm_fn=_norm)


# Request/Response Models
class SearchRequest(BaseModel):
    query: str
    top_k: int = 5


class SearchResponse(BaseModel):
    results: List[Dict[str, Any]]
    search_type: str


class AgentRequest(BaseModel):
    question: str


class AgentResponse(BaseModel):
    answer: str
    sources: list[SourceItem]
    confidence_note: str


class RebuildIndexRequest(BaseModel):
    root_dir: str

class CloneAndRebuildRequest(BaseModel):
    destination: str
    remote_repo: str

# ============================================
# Search Endpoints
# ============================================

@app.post("/keyword-search", response_model=SearchResponse)
async def keyword_search(payload: SearchRequest, request: Request):
    """BM25-based keyword search (sparse retrieval)."""
    try:
        bm25_retriever = BM25Retriever.from_documents(request.app.state.all_chunks)
        bm25_retriever.k = payload.top_k

        docs = bm25_retriever.invoke(payload.query)

        results = [
            {
                "content": doc.page_content,
                "metadata": doc.metadata,
                "score": None
            }
            for doc in docs
        ]

        return SearchResponse(results=results, search_type="keyword")

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/semantic-search", response_model=SearchResponse)
async def semantic_search(payload: SearchRequest, request: Request):
    """Dense vector similarity search using FAISS."""
    try:
        docs_with_scores = request.app.state.vectorstore.similarity_search_with_score(
            payload.query,
            k=payload.top_k
        )

        results = [
            {
                "content": doc.page_content,
                "metadata": doc.metadata,
                "score": float(score),
                "file_path": doc.metadata.get('file_path', 'unknown')
            }
            for doc, score in docs_with_scores
        ]

        return SearchResponse(results=results, search_type="semantic")

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/hybrid-search", response_model=SearchResponse)
async def hybrid_search(payload: SearchRequest, request: Request):
    """Hybrid search combining BM25 + semantic (Reciprocal Rank Fusion)."""
    try:
        bm25_retriever = BM25Retriever.from_documents(request.app.state.all_chunks)
        bm25_retriever.k = payload.top_k * 2

        faiss_retriever = request.app.state.vectorstore.as_retriever(
            search_kwargs={"k": payload.top_k * 2}
        )

        ensemble_retriever = EnsembleRetriever(
            retrievers=[bm25_retriever, faiss_retriever],
            weights=[0.4, 0.6]
        )

        docs = ensemble_retriever.invoke(payload.query)

        results = [
            {
                "content": doc.page_content,
                "metadata": doc.metadata,
                "score": None,
                "file_path": doc.metadata.get('file_path', 'unknown')
            }
            for doc in docs[:payload.top_k]
        ]

        return SearchResponse(results=results, search_type="hybrid")

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health_check(request: Request):
    """Health check endpoint."""
    return {
        "status": "healthy",
        "vectorstore_size": request.app.state.vectorstore.index.ntotal,
        "total_chunks": len(request.app.state.all_chunks),
        "codebase_path": CODEBASE_PATH,
        "embedding_model": EMBEDDING_MODEL
    }


@app.post("/rebuild-index")
async def rebuild_index(payload: RebuildIndexRequest, request: Request):
    global CODEBASE_PATH

    try:
        root_dir = payload.root_dir

        if not os.path.isdir(root_dir):
            raise HTTPException(status_code=400, detail=f"Directory does not exist: {root_dir}")

        CODEBASE_PATH = root_dir

        if os.path.exists(CACHE_FILE):
            os.remove(CACHE_FILE)

        vectorstore, all_chunks, embeddings = initialize_rag_system(root_dir)

        request.app.state.vectorstore = vectorstore
        request.app.state.all_chunks = all_chunks
        request.app.state.embeddings = embeddings

        request.app.state.agent = create_agent_executor(
            vectorstore=request.app.state.vectorstore,
            all_chunks=request.app.state.all_chunks,
            semgrep_vector_store=request.app.state.semgrep_vectorstore,
            srm_store=request.app.state.srm_store,
            cwe_store=request.app.state.cwe_store,
            norm_fn=_norm,
        )

        return {
            "status": "success",
            "message": "Index rebuilt successfully",
            "root_dir": root_dir,
            "chunks": len(request.app.state.all_chunks),
            "vectors": request.app.state.vectorstore.index.ntotal,
        }

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/clone-and-rebuild")
async def clone_and_rebuild(payload: CloneAndRebuildRequest, request: Request):
    global CODEBASE_PATH

    try:
        destination = os.path.abspath(payload.destination)
        remote_repo = payload.remote_repo.strip()

        parent_dir = os.path.dirname(destination)
        if parent_dir and not os.path.isdir(parent_dir):
            os.makedirs(parent_dir, exist_ok=True)

        if os.path.exists(destination):
            shutil.rmtree(destination)

        Repo.clone_from(remote_repo, destination)

        CODEBASE_PATH = destination

        if os.path.exists(CACHE_FILE):
            os.remove(CACHE_FILE)

        vectorstore, all_chunks, embeddings = initialize_rag_system(destination)

        request.app.state.vectorstore = vectorstore
        request.app.state.all_chunks = all_chunks
        request.app.state.embeddings = embeddings

        request.app.state.agent = create_agent_executor(
            vectorstore=request.app.state.vectorstore,
            all_chunks=request.app.state.all_chunks,
            semgrep_vector_store=request.app.state.semgrep_vectorstore,
            srm_store=request.app.state.srm_store,
            cwe_store=request.app.state.cwe_store,
            norm_fn=_norm,
        )

        return {
            "status": "success",
            "message": "Repository cloned and index rebuilt successfully",
            "remote_repo": remote_repo,
            "destination": destination,
            "chunks": len(request.app.state.all_chunks),
            "vectors": request.app.state.vectorstore.index.ntotal,
        }

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/agent/explainer", response_model=AgentResponse)
async def ask_agent(payload: AgentRequest, request: Request):
    try:
        result = request.app.state.agent.invoke(
            {"messages": [{"role": "user", "content": payload.question}]},
            config={
                "callbacks": [langfuse_handler],
                "run_name": "safe_retriever_agent",
                "tags": ["agent:safe_retriever"],
                "metadata": {"agent": "safe_retriever"}
            }
        )
        print(f"RESULT: {result}")

        structured = result["structured_response"]

        return AgentResponse(
            answer=structured.answer,
            sources=structured.sources,
            confidence_note=structured.confidence_note
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============================================
# Run the API
# ============================================

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8800)
