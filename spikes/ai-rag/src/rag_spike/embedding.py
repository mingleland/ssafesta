"""GMS의 OpenAI·Gemini Embeddings REST API를 호출하고 1536차원 계약을 강제한다."""

from __future__ import annotations

import math
import time
from collections.abc import Mapping, Sequence
from typing import Any, Protocol

from .models import EmbeddingBatch, ModelSpec, TARGET_DIMENSION


DEFAULT_OPENAI_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/embeddings"
DEFAULT_GEMINI_URL = (
    "https://gms.ssafy.io/gmsapi/generativelanguage.googleapis.com/"
    "v1beta/models/gemini-embedding-2:embedContent"
)


class JsonPoster(Protocol):
    def __call__(self, payload: dict[str, Any]) -> dict[str, Any]: ...


class DimensionMismatchError(ValueError):
    pass


class GmsEmbeddingClient:
    def __init__(
        self,
        *,
        api_key: str,
        openai_url: str = DEFAULT_OPENAI_URL,
        gemini_url: str = DEFAULT_GEMINI_URL,
        batch_size: int = 64,
        timeout_seconds: float = 60.0,
        openai_poster: JsonPoster | None = None,
        gemini_poster: JsonPoster | None = None,
    ) -> None:
        if not api_key.strip():
            raise ValueError("GMS_API_KEY가 비어 있습니다.")
        urls = (
            ("GMS_OPENAI_EMBEDDINGS_URL", openai_url),
            ("GMS_GEMINI_EMBEDDINGS_URL", gemini_url),
        )
        for name, url in urls:
            if not url.startswith(("https://", "http://")):
                raise ValueError(f"{name}은 http(s) URL이어야 합니다.")
        if batch_size <= 0:
            raise ValueError("batch_size는 1 이상이어야 합니다.")
        self._batch_size = batch_size
        self._openai_poster = openai_poster or self._build_http_poster(
            url=openai_url,
            headers={"Authorization": f"Bearer {api_key}"},
            timeout_seconds=timeout_seconds,
        )
        self._gemini_poster = gemini_poster or self._build_http_poster(
            url=gemini_url,
            headers={"x-goog-api-key": api_key},
            timeout_seconds=timeout_seconds,
        )

    @staticmethod
    def _build_http_poster(
        *, url: str, headers: Mapping[str, str], timeout_seconds: float
    ) -> JsonPoster:
        try:
            import httpx
        except ImportError as exc:  # pragma: no cover
            raise RuntimeError("httpx가 필요합니다. Conda 환경을 먼저 생성하세요.") from exc
        client = httpx.Client(
            headers={**headers, "Content-Type": "application/json"},
            timeout=timeout_seconds,
        )

        def post(payload: dict[str, Any]) -> dict[str, Any]:
            try:
                response = client.post(url, json=payload)
                response.raise_for_status()
                body = response.json()
            except httpx.HTTPError:
                raise RuntimeError(
                    "GMS Embedding 요청에 실패했습니다. HTTP 상태와 GMS 콘솔을 확인하세요."
                ) from None
            except ValueError:
                raise RuntimeError("GMS Embedding 응답이 올바른 JSON이 아닙니다.") from None
            if not isinstance(body, dict):
                raise ValueError("Embedding API 응답이 JSON 객체가 아닙니다.")
            return body

        return post

    def embed(self, texts: Sequence[str], model: ModelSpec) -> EmbeddingBatch:
        if not texts:
            return EmbeddingBatch([], 0.0, 0, 0)
        if model.provider == "openai":
            return self._embed_openai(texts, model)
        if model.provider == "gemini":
            return self._embed_gemini(texts, model)
        raise ValueError(f"지원하지 않는 Embedding provider: {model.provider}")

    def _embed_openai(self, texts: Sequence[str], model: ModelSpec) -> EmbeddingBatch:
        started = time.perf_counter()
        vectors: list[list[float]] = []
        request_count = 0
        for offset in range(0, len(texts), self._batch_size):
            inputs = list(texts[offset : offset + self._batch_size])
            body = self._openai_poster(
                {"model": model.model_id, "input": inputs, "dimensions": TARGET_DIMENSION}
            )
            request_count += 1
            data = body.get("data")
            if not isinstance(data, list) or len(data) != len(inputs):
                raise ValueError("OpenAI Embedding 응답 개수가 요청 입력 개수와 다릅니다.")
            ordered = sorted(data, key=lambda item: int(item.get("index", 0)))
            for item in ordered:
                vector = item.get("embedding")
                if not isinstance(vector, list):
                    raise ValueError("OpenAI Embedding 응답에 embedding 배열이 없습니다.")
                vectors.append(_validated_vector(vector, model.model_id))
        return EmbeddingBatch(
            vectors=vectors,
            elapsed_seconds=time.perf_counter() - started,
            request_count=request_count,
            input_count=len(texts),
        )

    def _embed_gemini(self, texts: Sequence[str], model: ModelSpec) -> EmbeddingBatch:
        started = time.perf_counter()
        vectors: list[list[float]] = []
        for text in texts:
            body = self._gemini_poster(
                {
                    "content": {"parts": [{"text": text}]},
                    "outputDimensionality": TARGET_DIMENSION,
                }
            )
            embedding = body.get("embedding")
            values = embedding.get("values") if isinstance(embedding, dict) else None
            if not isinstance(values, list):
                raise ValueError("Gemini Embedding 응답에 embedding.values 배열이 없습니다.")
            vectors.append(_validated_vector(values, model.model_id))
        return EmbeddingBatch(
            vectors=vectors,
            elapsed_seconds=time.perf_counter() - started,
            request_count=len(texts),
            input_count=len(texts),
        )


def _validated_vector(vector: Sequence[Any], model_id: str) -> list[float]:
    validated = [float(value) for value in vector]
    if len(validated) != TARGET_DIMENSION:
        raise DimensionMismatchError(
            f"{model_id} 응답 차원은 {len(validated)}입니다. 필요한 차원은 {TARGET_DIMENSION}입니다. "
            "GMS가 차원 파라미터를 전달하는지 확인하세요."
        )
    if not all(math.isfinite(value) for value in validated):
        raise ValueError(f"{model_id} 응답에 NaN 또는 무한대가 포함되어 있습니다.")
    return validated
