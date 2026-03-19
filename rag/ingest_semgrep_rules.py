import os
import json
import subprocess
from pathlib import Path

import yaml
from dotenv import load_dotenv
from langchain_core.documents import Document
from langchain_openai import AzureOpenAIEmbeddings
from langchain_openai import OpenAIEmbeddings

from langchain_community.vectorstores import FAISS

load_dotenv()




def clone_or_pull(repo_url: str, target_dir: Path):
    if target_dir.exists() and (target_dir / ".git").exists():
        subprocess.run(["git", "-C", str(target_dir), "pull"], check=True)
    else:
        target_dir.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", repo_url, str(target_dir)], check=True)


def iter_yaml_files(base_dir: Path):
    if not base_dir.exists():
        return
    for p in base_dir.rglob("*"):
        if p.is_file() and p.suffix.lower() in {".yml", ".yaml"}:
            yield p


def parse_rule_metadata(raw_text: str, file_path: Path, source_type: str) -> dict:
    try:
        data = yaml.safe_load(raw_text) or {}
    except Exception:
        data = {}

    rules = data.get("rules", [])
    if not isinstance(rules, list):
        rules = []

    rule_ids = []
    severities = []
    languages = []
    messages = []

    for rule in rules:
        if not isinstance(rule, dict):
            continue
        if rule.get("id"):
            rule_ids.append(rule["id"])
        if rule.get("severity"):
            severities.append(str(rule["severity"]))
        if rule.get("message"):
            messages.append(str(rule["message"]))
        langs = rule.get("languages", [])
        if isinstance(langs, list):
            languages.extend([str(x) for x in langs])

    return {
        "source_type": source_type,                # community | pro
        "file_path": str(file_path),
        "file_name": file_path.name,
        "rule_count": len(rules),
        "rule_ids": sorted(set(rule_ids)),
        "languages": sorted(set(languages)),
        "severities": sorted(set(severities)),
        "messages": messages[:10],
        "doc_type": "semgrep_rule_file",
    }


def rule_file_to_document(file_path: Path, source_type: str) -> Document:
    raw_text = file_path.read_text(encoding="utf-8", errors="ignore")
    metadata = parse_rule_metadata(raw_text, file_path, source_type)
    return Document(page_content=raw_text, metadata=metadata)


def load_rule_documents(community_dir, pro_dir) -> list[Document]:
    docs = []

    for path in iter_yaml_files(community_dir):
        docs.append(rule_file_to_document(path, "community"))

    if pro_dir.exists():
        for path in iter_yaml_files(pro_dir):
            docs.append(rule_file_to_document(path, "pro"))

    return docs


def save_manifest(docs: list[Document], index_dir, manifest_path):
    manifest = []
    for d in docs:
        manifest.append({
            "file_path": d.metadata.get("file_path"),
            "file_name": d.metadata.get("file_name"),
            "source_type": d.metadata.get("source_type"),
            "rule_count": d.metadata.get("rule_count"),
            "rule_ids": d.metadata.get("rule_ids"),
            "languages": d.metadata.get("languages"),
            "severities": d.metadata.get("severities"),
        })

    index_dir.mkdir(parents=True, exist_ok=True)
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)


def build_semgrep_index(index_dir, community_repo_url, community_dir, pro_dir, embedding_model, manifest_path, force_rebuild=False):
    # embeddings = AzureOpenAIEmbeddings(
    #     model=embedding_model,
    #     azure_endpoint=os.getenv("AZURE_OPENAI_ENDPOINT"),
    #     api_key=os.getenv("AZURE_OPENAI_API_KEY"),
    #     api_version=os.getenv("AZURE_OPENAI_API_VERSION"),
    #     azure_deployment=os.getenv("AZURE_OPENAI_EMBEDDING_DEPLOYMENT")
    # )

    embeddings = OpenAIEmbeddings(model=embedding_model)

    faiss_file = index_dir / "index.faiss"
    pkl_file = index_dir / "index.pkl"

    # Load existing index if present
    if not force_rebuild and faiss_file.exists() and pkl_file.exists():
        print(f"Loading existing Semgrep index from {index_dir}")
        return FAISS.load_local(
            str(index_dir),
            embeddings,
            allow_dangerous_deserialization=True
        )

    # Otherwise rebuild
    clone_or_pull(community_repo_url, community_dir)

    docs = load_rule_documents(community_dir, pro_dir)
    if not docs:
        raise ValueError("No Semgrep rule documents found.")

    vectorstore = FAISS.from_documents(docs, embeddings)

    index_dir.mkdir(parents=True, exist_ok=True)
    vectorstore.save_local(str(index_dir))
    save_manifest(docs, index_dir, manifest_path)

    print(f"Indexed {len(docs)} rule files")
    print(f"Saved index to {index_dir}")

    return vectorstore



# if __name__ == "__main__":
#     build_semgrep_index()
