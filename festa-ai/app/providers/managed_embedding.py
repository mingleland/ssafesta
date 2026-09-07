"""OpenAI 호환 GMS 응답을 안전한 1536차원 Embedding 계약으로 변환한다."""

from __future__ import annotations

import math
import logging
import time
from collections.abc import Sequence
from urllib.parse import urljoin

import httpx
from pydantic import SecretStr

from app.providers.embedding import EmbeddingBatch, EmbeddingVector


logger = logging.getLogger(__name__)


class ManagedEmbeddingError(RuntimeError):
    """Provider 원문이나 Secret을 포함하지 않는 정규화 오류다."""

    def __init__(
        self,
        code: str,
        *,
        retryable: bool,
        status_code: int | None = None,
    ) -> None:
        message = code
        if status_code is not None:
            message += f" (status={status_code})"
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.status_code = status_code


class ManagedEmbeddingProvider:
    """GMS Embedding HTTP 호출과 응답 형식·개수·차원 검증을 담당한다."""

    dimension = 1536

    def __init__(
        self,
        *,
        api_base_url: str,
        api_path: str,
        api_key: SecretStr,
        model_id: str,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.model_id = model_id
        self._api_url = urljoin(
            f"{api_base_url.rstrip('/')}/", api_path.lstrip("/")
        )
        self._api_key = api_key
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(60.0, connect=10.0)
        )

    async def embed(self, texts: Sequence[str]) -> EmbeddingBatch:
        normalized_texts = tuple(texts)
        if not normalized_texts:
            return EmbeddingBatch(model_id=self.model_id, vectors=())

        started = time.perf_counter()
        response_data = await self._request_embeddings(normalized_texts)
        vectors = self._parse_vectors(response_data, expected_count=len(normalized_texts))
        logger.info(
            "gms_embedding_usage model_id=%s request_count=1 input_count=%d elapsed_ms=%s",
            self.model_id,
            len(normalized_texts),
            round((time.perf_counter() - started) * 1_000, 2),
        )
        return EmbeddingBatch(model_id=self.model_id, vectors=vectors)

    async def aclose(self) -> None:
        """직접 생성한 HTTP client만 닫고 주입받은 client의 수명은 호출자에게 맡긴다."""
        if self._owns_client:
            await self._client.aclose()

    async def _request_embeddings(self, texts: tuple[str, ...]) -> object:
        headers = {
            "Authorization": f"Bearer {self._api_key.get_secret_value()}"
        }
        payload = {
            "model": self.model_id,
            "input": list(texts),
            "dimensions": self.dimension,
        }

        try:
            response = await self._client.post(
                self._api_url,
                headers=headers,
                json=payload,
            )
            response.raise_for_status()
            return response.json()
        except httpx.TimeoutException as exc:
            raise ManagedEmbeddingError(
                "EMBEDDING_TIMEOUT", retryable=True
            ) from exc
        except httpx.HTTPStatusError as exc:
            status_code = exc.response.status_code
            raise ManagedEmbeddingError(
                "EMBEDDING_PROVIDER_ERROR",
                retryable=status_code == 429 or status_code >= 500,
                status_code=status_code,
            ) from exc
        except httpx.RequestError as exc:
            raise ManagedEmbeddingError(
                "EMBEDDING_PROVIDER_ERROR", retryable=True
            ) from exc
        except ValueError as exc:
            raise ManagedEmbeddingError(
                "EMBEDDING_INVALID_RESPONSE", retryable=False
            ) from exc

    def _parse_vectors(
        self,
        response_data: object,
        *,
        expected_count: int,
    ) -> tuple[EmbeddingVector, ...]:
        if not isinstance(response_data, dict):
            raise ManagedEmbeddingError(
                "EMBEDDING_INVALID_RESPONSE", retryable=False
            )

        items = response_data.get("data")
        if not isinstance(items, list) or len(items) != expected_count:
            raise ManagedEmbeddingError(
                "EMBEDDING_INVALID_RESPONSE", retryable=False
            )

        ordered_items = self._order_items(items)
        vectors: list[EmbeddingVector] = []
        for item in ordered_items:
            if not isinstance(item, dict):
                raise ManagedEmbeddingError(
                    "EMBEDDING_INVALID_RESPONSE", retryable=False
                )
            raw_vector = item.get("embedding")
            if not isinstance(raw_vector, list) or len(raw_vector) != self.dimension:
                raise ManagedEmbeddingError(
                    "EMBEDDING_DIMENSION_MISMATCH", retryable=False
                )
            if any(
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not math.isfinite(float(value))
                for value in raw_vector
            ):
                raise ManagedEmbeddingError(
                    "EMBEDDING_INVALID_RESPONSE", retryable=False
                )
            vectors.append(tuple(float(value) for value in raw_vector))

        return tuple(vectors)

    @staticmethod
    def _order_items(items: list[object]) -> list[dict[str, object]]:
        if any(not isinstance(item, dict) for item in items):
            raise ManagedEmbeddingError(
                "EMBEDDING_INVALID_RESPONSE", retryable=False
            )

        indexed_items: list[dict[str, object]] = [
            item for item in items if isinstance(item, dict)
        ]
        # TODO(S15P21A604-94): GMS 모델·API 확정 후 index 제공 여부와
        # index 없는 응답의 순서 보장 계약을 실제 명세로 다시 검증한다.
        if all("index" not in item for item in indexed_items):
            return indexed_items

        indexes = [item.get("index") for item in indexed_items]
        expected_indexes = list(range(len(items)))
        if (
            any(isinstance(index, bool) or not isinstance(index, int) for index in indexes)
            or sorted(indexes) != expected_indexes
        ):
            raise ManagedEmbeddingError(
                "EMBEDDING_INVALID_RESPONSE", retryable=False
            )
        return sorted(indexed_items, key=lambda item: int(item["index"]))
