import os
from typing import List, Optional, Any

import tiktoken
from pydantic import PrivateAttr
from langfuse import get_client
from langchain_openai import AzureOpenAIEmbeddings

langfuse = get_client()


class TracedAzureOpenAIEmbeddings(AzureOpenAIEmbeddings):
    _price_per_1m_tokens: Optional[float] = PrivateAttr(default=None)
    _encoding: Any = PrivateAttr(default=None)
    _model_name_for_tracing: str = PrivateAttr(default="text-embedding-3-large")
    _deployment_name_for_tracing: Optional[str] = PrivateAttr(default=None)

    def __init__(self, *args, price_per_1m_tokens: Optional[float] = None, **kwargs):
        super().__init__(*args, **kwargs)

        self._price_per_1m_tokens = price_per_1m_tokens
        self._model_name_for_tracing = kwargs.get("model") or "text-embedding-3-large"
        self._deployment_name_for_tracing = kwargs.get("azure_deployment")

        try:
            self._encoding = tiktoken.encoding_for_model(self._model_name_for_tracing)
        except Exception:
            self._encoding = tiktoken.get_encoding("cl100k_base")

    def _count_tokens(self, texts: List[str]) -> int:
        return sum(len(self._encoding.encode(t or "")) for t in texts)

    def _calc_cost(self, input_tokens: int) -> Optional[float]:
        if self._price_per_1m_tokens is None:
            return None
        return (input_tokens / 1_000_000) * float(self._price_per_1m_tokens)

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        input_tokens = self._count_tokens(texts)

        with langfuse.start_as_current_observation(
                as_type="generation",
                name="azure-openai-embeddings-batch",
                model=self._model_name_for_tracing,
                input={
                    "batch_size": len(texts),
                    "estimated_input_tokens": input_tokens,
                    "deployment": self._deployment_name_for_tracing,
                },
        ) as generation:
            vectors = super().embed_documents(texts)

            update_data = {
                "output": {
                    "vector_count": len(vectors),
                    "dimensions": len(vectors[0]) if vectors else 0,
                },
                "usage_details": {
                    "input": input_tokens,
                    "total": input_tokens,
                },
            }

            cost = self._calc_cost(input_tokens)
            if cost is not None:
                update_data["cost_details"] = {
                    "input": cost,
                    "total": cost,
                }

            generation.update(**update_data)
            return vectors

    def embed_query(self, text: str) -> List[float]:
        input_tokens = self._count_tokens([text])

        with langfuse.start_as_current_observation(
                as_type="generation",
                name="azure-openai-embedding-query",
                model=self._model_name_for_tracing,
                input={
                    "estimated_input_tokens": input_tokens,
                    "deployment": self._deployment_name_for_tracing,
                },
        ) as generation:
            vector = super().embed_query(text)

            update_data = {
                "output": {
                    "dimensions": len(vector) if vector else 0,
                },
                "usage_details": {
                    "input": input_tokens,
                    "total": input_tokens,
                },
            }

            cost = self._calc_cost(input_tokens)
            if cost is not None:
                update_data["cost_details"] = {
                    "input": cost,
                    "total": cost,
                }

            generation.update(**update_data)
            return vector
