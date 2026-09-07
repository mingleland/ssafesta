"""검색(top-K) 결과에 4가지 방법 중 하나를 적용해 top-N을 만들고 품질 지표를 낸다.

Reranker 실패는 삼키지 않는다 — ``rerank_failure_count``로 드러내고, 실패한
질문은 bi-encoder 원 순서(top-N 절단)로 대체해 나머지 질문 평가를 계속한다.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import asdict
from typing import Any

from .evaluation import evaluate_reranked_hits
from .models import EvalCase, ModelSpec
from .reranker import CrossEncoderReranker, GmsLlmReranker, RerankMethod, cutoff_by_similarity, truncate_top_n
from .store import VectorStore


def evaluate_rerank_method(
    *,
    store: VectorStore,
    run_id: str,
    model_id: str,
    booth_id: int,
    agent_id: int,
    cases: Sequence[EvalCase],
    query_vectors: Sequence[Sequence[float]],
    retrieval_top_k: int,
    top_n: int,
    method: RerankMethod,
    similarity_max_distance: float | None = None,
    cross_encoder: CrossEncoderReranker | None = None,
    gms_llm: GmsLlmReranker | None = None,
    llm_model: ModelSpec | None = None,
) -> dict[str, Any]:
    if method is RerankMethod.SIMILARITY_CUTOFF and similarity_max_distance is None:
        raise ValueError("similarity_cutoff 방식은 similarity_max_distance가 필요합니다.")
    if method is RerankMethod.CROSS_ENCODER and cross_encoder is None:
        raise ValueError("cross_encoder 방식은 cross_encoder 인스턴스가 필요합니다.")
    if method is RerankMethod.GMS_LLM and (gms_llm is None or llm_model is None):
        raise ValueError("gms_llm 방식은 gms_llm과 llm_model이 모두 필요합니다.")

    hit_lists = []
    rerank_elapsed = 0.0
    rerank_request_count = 0
    estimated_rerank_credits = 0.0
    rerank_failure_count = 0

    for case, vector in zip(cases, query_vectors, strict=True):
        hits = store.search(
            run_id=run_id,
            model_id=model_id,
            booth_id=booth_id,
            agent_id=agent_id,
            query_vector=vector,
            top_k=retrieval_top_k,
        )
        if method is RerankMethod.NONE:
            selected = truncate_top_n(hits, min(top_n, len(hits))) if hits else []
        elif method is RerankMethod.SIMILARITY_CUTOFF:
            cut = cutoff_by_similarity(hits, max_distance=similarity_max_distance)
            selected = truncate_top_n(cut, min(top_n, len(cut))) if cut else []
        elif method is RerankMethod.CROSS_ENCODER:
            result = cross_encoder.rerank(query=case.query, hits=hits, top_n=top_n)
            selected = list(result.hits)
            rerank_elapsed += result.elapsed_seconds
            rerank_request_count += result.request_count
            estimated_rerank_credits += result.estimated_credits
            if not result.success:
                rerank_failure_count += 1
        elif method is RerankMethod.GMS_LLM:
            result = gms_llm.rerank(query=case.query, hits=hits, top_n=top_n, model=llm_model)
            selected = list(result.hits)
            rerank_elapsed += result.elapsed_seconds
            rerank_request_count += result.request_count
            estimated_rerank_credits += result.estimated_credits
            if not result.success:
                rerank_failure_count += 1
        else:
            raise ValueError(f"지원하지 않는 rerank 방식: {method}")
        hit_lists.append(selected)

    metrics = evaluate_reranked_hits(cases, hit_lists)
    return {
        "method": method.value,
        "retrieval_top_k": retrieval_top_k,
        "top_n": top_n,
        "rerank_elapsed_seconds": rerank_elapsed,
        "rerank_request_count": rerank_request_count,
        "estimated_rerank_credits": estimated_rerank_credits,
        "rerank_failure_count": rerank_failure_count,
        **asdict(metrics),
    }
