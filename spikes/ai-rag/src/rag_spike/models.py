"""스파이크에서 비교할 모델과 검색 평가 데이터를 정의한다."""

from __future__ import annotations

from dataclasses import dataclass


TARGET_DIMENSION = 1536


@dataclass(frozen=True)
class ModelSpec:
    model_id: str
    provider: str
    credit_per_request: float


MODEL_SPECS: dict[str, ModelSpec] = {
    "text-embedding-3-small": ModelSpec(
        model_id="text-embedding-3-small",
        provider="openai",
        credit_per_request=0.001,
    ),
    "text-embedding-3-large": ModelSpec(
        model_id="text-embedding-3-large",
        provider="openai",
        credit_per_request=0.01,
    ),
    "gemini-embedding-2": ModelSpec(
        model_id="gemini-embedding-2",
        provider="gemini",
        credit_per_request=0.2,
    ),
}


@dataclass(frozen=True)
class PageText:
    page: int
    text: str


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    page: int
    chunk_no: int
    token_count: int
    content: str


@dataclass(frozen=True)
class EvalCase:
    case_id: str
    query: str
    relevant_pages: frozenset[int]
    relevant_contains: tuple[str, ...] = ()


@dataclass(frozen=True)
class SearchHit:
    chunk_id: str
    page: int
    content: str
    distance: float
    booth_id: int
    agent_id: int


@dataclass(frozen=True)
class EmbeddingBatch:
    vectors: list[list[float]]
    elapsed_seconds: float
    request_count: int
    input_count: int
