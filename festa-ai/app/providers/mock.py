"""GMS 키 없이 문서·대화 파이프라인을 재현 가능하게 실행하는 Mock Provider다."""

from __future__ import annotations

import hashlib
import math
from collections.abc import AsyncIterator, Sequence

from app.providers.embedding import EmbeddingBatch, EmbeddingVector
from app.providers.llm import LLMRequest, LLMToken


EMBEDDING_DIMENSION = 1536
_LLM_TOKEN_CHUNK_SIZE = 24


class MockEmbeddingProvider:
    """동일 문자열에 항상 동일한 1536차원 단위 벡터를 반환한다."""

    model_id = "mock-hash-embedding-1536-v1"
    dimension = EMBEDDING_DIMENSION

    def __init__(self) -> None:
        self.calls: list[tuple[str, ...]] = []

    async def embed(self, texts: Sequence[str]) -> EmbeddingBatch:
        normalized_texts = tuple(texts)
        self.calls.append(normalized_texts)
        return EmbeddingBatch(
            model_id=self.model_id,
            vectors=tuple(self._vector_for(text) for text in normalized_texts),
        )

    @staticmethod
    def _vector_for(text: str) -> EmbeddingVector:
        encoded = text.encode("utf-8")
        values: list[float] = []

        for block_index in range(EMBEDDING_DIMENSION // hashlib.sha256().digest_size):
            digest = hashlib.sha256(
                encoded + block_index.to_bytes(length=2, byteorder="big")
            ).digest()
            values.extend((byte - 127.5) / 127.5 for byte in digest)

        norm = math.sqrt(sum(value * value for value in values))
        return tuple(value / norm for value in values)


class MockLLMProvider:
    """마지막 사용자 메시지를 기반으로 결정적 token stream을 반환한다."""

    model_id = "mock-context-llm-v1"

    def __init__(self) -> None:
        self.calls: list[LLMRequest] = []

    async def stream(self, request: LLMRequest) -> AsyncIterator[LLMToken]:
        self.calls.append(request)
        response = self._response_for(request)

        for start in range(0, len(response), _LLM_TOKEN_CHUNK_SIZE):
            yield LLMToken(text=response[start : start + _LLM_TOKEN_CHUNK_SIZE])

    @staticmethod
    def _response_for(request: LLMRequest) -> str:
        user_messages = [
            message.content for message in request.messages if message.role == "user"
        ]
        if not user_messages:
            return "[Mock 답변] 사용자 질문이 없습니다."
        return f"[Mock 답변] {user_messages[-1]}"
