"""검색 결과에서 Recall@K, MRR, P95와 격리 위반을 계산한다."""

from __future__ import annotations

import math
import time
from collections.abc import Sequence
from dataclasses import dataclass

from .models import EvalCase, SearchHit
from .store import VectorStore


@dataclass(frozen=True)
class RetrievalMetrics:
    recall_at_k: float
    mrr: float
    p50_search_ms: float
    p95_search_ms: float
    mean_context_tokens: float
    p95_context_tokens: float
    leakage_count: int
    evaluated_queries: int
    missed_case_ids: tuple[str, ...]


def evaluate_retrieval(
    *,
    store: VectorStore,
    run_id: str,
    model_id: str,
    booth_id: int,
    agent_id: int,
    cases: Sequence[EvalCase],
    query_vectors: Sequence[Sequence[float]],
    top_k: int,
) -> RetrievalMetrics:
    if top_k <= 0:
        raise ValueError("top_k는 1 이상이어야 합니다.")
    if len(cases) != len(query_vectors):
        raise ValueError("평가 질문 수와 질문 벡터 수가 다릅니다.")
    if not cases:
        raise ValueError("평가 질문이 비어 있습니다.")
    reciprocal_ranks: list[float] = []
    latencies_ms: list[float] = []
    context_tokens: list[int] = []
    missed_case_ids: list[str] = []
    leakage_count = 0
    matched = 0
    for case, vector in zip(cases, query_vectors, strict=True):
        started = time.perf_counter()
        hits = store.search(
            run_id=run_id,
            model_id=model_id,
            booth_id=booth_id,
            agent_id=agent_id,
            query_vector=vector,
            top_k=top_k,
        )
        latencies_ms.append((time.perf_counter() - started) * 1000)
        context_tokens.append(sum(hit.context_token_count for hit in hits))
        leakage_count += sum(
            hit.booth_id != booth_id or hit.agent_id != agent_id for hit in hits
        )
        leakage_count += len(
            store.search(
                run_id=run_id,
                model_id=model_id,
                booth_id=booth_id + 1,
                agent_id=agent_id,
                query_vector=vector,
                top_k=top_k,
            )
        )
        leakage_count += len(
            store.search(
                run_id=run_id,
                model_id=model_id,
                booth_id=booth_id,
                agent_id=agent_id + 1,
                query_vector=vector,
                top_k=top_k,
            )
        )
        rank = _first_relevant_rank(case, hits)
        if rank is not None:
            matched += 1
            reciprocal_ranks.append(1.0 / rank)
        else:
            reciprocal_ranks.append(0.0)
            missed_case_ids.append(case.case_id)
    return RetrievalMetrics(
        recall_at_k=matched / len(cases),
        mrr=sum(reciprocal_ranks) / len(reciprocal_ranks),
        p50_search_ms=_percentile(latencies_ms, 0.50),
        p95_search_ms=_percentile(latencies_ms, 0.95),
        mean_context_tokens=sum(context_tokens) / len(context_tokens),
        p95_context_tokens=_percentile(context_tokens, 0.95),
        leakage_count=leakage_count,
        evaluated_queries=len(cases),
        missed_case_ids=tuple(missed_case_ids),
    )


def _first_relevant_rank(case: EvalCase, hits: Sequence[SearchHit]) -> int | None:
    for rank, hit in enumerate(hits, 1):
        page_match = bool(case.relevant_pages) and hit.page in case.relevant_pages
        content_match = any(marker in hit.context_content for marker in case.relevant_contains)
        if page_match or content_match:
            return rank
    return None


def _percentile(values: Sequence[float], percentile: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = max(0, math.ceil(percentile * len(ordered)) - 1)
    return ordered[index]
