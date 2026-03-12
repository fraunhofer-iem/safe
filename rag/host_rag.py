import os
import hashlib
import json
from typing import List, Dict, Any, Optional
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

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
from safeRetrieverAgent import create_agent_executor, SourceItem

# ============================================
# Configuration
# ============================================

load_dotenv()
#langfuse_handler = CallbackHandler()
CODEBASE_PATH = "../"  # Path to your code and docs
FAISS_INDEX_PATH = "./faiss_index"
CACHE_FILE = "./codebase_hash.json"
EMBEDDING_MODEL = "text-embedding-3-large"  # Later: "voyage-code-3"



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
    '.git', '.gitignore', '.DS_Store'
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

    for doc in docs:
        ext = doc.metadata.get('file_extension', '').lower()

        try:
            # 1. Code files: Syntax-aware chunking
            if ext in CODE_EXTENSIONS:
                language = CODE_EXTENSIONS[ext]
                splitter = RecursiveCharacterTextSplitter.from_language(
                    language=language,
                    chunk_size=500,  # Characters, not tokens
                    chunk_overlap=50
                )
                chunks = splitter.split_documents([doc])
                chunked_docs.extend(chunks)
                syntax_chunker +=1
                syntax_chunker_files.append(ext)


            # 2. Documentation files: Semantic chunking
            elif ext in DOC_EXTENSIONS:
                splitter = SemanticChunker(embeddings)
                chunks = splitter.split_documents([doc])
                chunked_docs.extend(chunks)
                sematic_chunker +=1
                semantic_chunker_files.append(ext)

            # 3. Unknown file types: Fallback to simple chunking
            else:
                splitter = RecursiveCharacterTextSplitter(
                    chunk_size=500,
                    chunk_overlap=50
                )
                chunks = splitter.split_documents([doc])
                chunked_docs.extend(chunks)
                default_chunker +=1
                default_chunker_files.append(ext)



        except Exception as e:
            print(f"Failed to chunk {doc.metadata.get('file_path')}: {e}")
            continue


    print(f"used syntax chunker on {syntax_chunker} | extensions: {set(syntax_chunker_files)}")
    print(f"used sematic chunker on {sematic_chunker} | extensions: {set(semantic_chunker_files)}")
    print(f"used default chunker on {default_chunker} | extensions: {set(default_chunker_files)}")
    return chunked_docs


# ============================================
# RAG Initialization with Caching
# ============================================

def initialize_rag_system(root_dir: str):
    if is_index_valid(root_dir):
        print("Valid FAISS index found. Loading from cache...")
        embeddings = OpenAIEmbeddings(model=EMBEDDING_MODEL)
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

    embeddings = OpenAIEmbeddings(model=EMBEDDING_MODEL)
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



# ============================================
# FastAPI Application
# ============================================

app = FastAPI(title="SAFE RAG Backend", version="1.0.0")

# Initialize on startup
vectorstore, all_chunks, embeddings = initialize_rag_system(CODEBASE_PATH)
agent = create_agent_executor(vectorstore, all_chunks)


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





# ============================================
# Search Endpoints
# ============================================

@app.post("/keyword-search", response_model=SearchResponse)
async def keyword_search(request: SearchRequest):
    """BM25-based keyword search (sparse retrieval)."""
    try:
        bm25_retriever = BM25Retriever.from_documents(all_chunks)
        bm25_retriever.k = request.top_k

        docs = bm25_retriever.invoke(request.query)

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
async def semantic_search(request: SearchRequest):
    """Dense vector similarity search using FAISS."""
    try:
        docs_with_scores = vectorstore.similarity_search_with_score(
            request.query,
            k=request.top_k
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
async def hybrid_search(request: SearchRequest):
    """Hybrid search combining BM25 + semantic (Reciprocal Rank Fusion)."""
    try:
        bm25_retriever = BM25Retriever.from_documents(all_chunks)
        bm25_retriever.k = request.top_k * 2  # Get more for better fusion

        faiss_retriever = vectorstore.as_retriever(
            search_kwargs={"k": request.top_k * 2}
        )

        # Ensemble with RRF (weights favor semantic slightly)
        ensemble_retriever = EnsembleRetriever(
            retrievers=[bm25_retriever, faiss_retriever],
            weights=[0.4, 0.6]  # Favor semantic search
        )

        docs = ensemble_retriever.invoke(request.query)

        results = [
            {
                "content": doc.page_content,
                "metadata": doc.metadata,
                "score": None,
                "file_path": doc.metadata.get('file_path', 'unknown')
            }
            for doc in docs[:request.top_k]
        ]

        return SearchResponse(results=results, search_type="hybrid")

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "vectorstore_size": vectorstore.index.ntotal,
        "total_chunks": len(all_chunks),
        "codebase_path": CODEBASE_PATH,
        "embedding_model": EMBEDDING_MODEL
    }


@app.post("/rebuild-index")
async def rebuild_index(request: RebuildIndexRequest):
    global vectorstore, all_chunks, embeddings, agent, CODEBASE_PATH

    try:
        root_dir = request.root_dir

        if not os.path.isdir(root_dir):
            raise HTTPException(status_code=400, detail=f"Directory does not exist: {root_dir}")

        CODEBASE_PATH = root_dir

        if os.path.exists(CACHE_FILE):
            os.remove(CACHE_FILE)

        vectorstore, all_chunks, embeddings = initialize_rag_system(root_dir)
        agent = create_agent_executor(vectorstore, all_chunks)

        return {
            "status": "success",
            "message": "Index rebuilt successfully",
            "root_dir": root_dir,
            "chunks": len(all_chunks),
            "vectors": vectorstore.index.ntotal
        }
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))




@app.post("/agent/explainer", response_model=AgentResponse)
async def ask_agent(request: AgentRequest):
    try:
        result = agent.invoke(
            {"messages": [{"role": "user", "content": request.question}]},
            # config={
            #     "callbacks": [langfuse_handler],
            #     "run_name": "safe_retriever_agent",
            #     "tags": ["agent:safe_retriever"],
            #     "metadata": {"agent": "safe_retriever"}
            # }
        )

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
