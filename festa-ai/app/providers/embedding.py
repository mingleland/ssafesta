"""문서 처리 서비스가 특정 Embedding 공급자에 의존하지 않게 하는 계약이다."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, Sequence, runtime_checkable


type EmbeddingVector = tuple[float, ...]


@dataclass(frozen=True, slots=True)
class EmbeddingBatch:
    """입력 순서대로 정규화된 Embedding 결과와 모델 식별자를 전달한다."""

    model_id: str
    vectors: tuple[EmbeddingVector, ...]


@runtime_checkable
class EmbeddingProvider(Protocol):
    """문자열 batch를 공급자 독립적인 Embedding 결과로 변환한다."""

    @property
    def model_id(self) -> str:
        """Chunk에 기록할 안정적인 Embedding 모델 식별자다."""
        ...

    @property
    def dimension(self) -> int:
        """Provider가 반환한다고 선언한 벡터 차원이다."""
        ...

    async def embed(self, texts: Sequence[str]) -> EmbeddingBatch:
        """입력과 동일한 개수·순서의 벡터를 반환한다."""
        ...
