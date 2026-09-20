"""370에서 확정된 청킹·Top-K 기준 위에서 4가지 reranking 방식을 비교한다."""

from __future__ import annotations

import time
import uuid
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from .benchmark import load_eval_cases
from .chunking import TikTokenCodec, chunk_pages
from .embedding import GmsEmbeddingClient
from .models import ModelSpec
from .pdf_loader import load_document_pages
from .rerank_evaluation import evaluate_rerank_method
from .reranker import CrossEncoderReranker, GmsLlmReranker, RerankMethod
from .store import VectorStore


def run_rerank_benchmark(
    *,
    pdf_path: Path,
    eval_path: Path,
    model: ModelSpec,
    chunk_size: int,
    overlap_ratio: float,
    retrieval_top_k: int,
    top_ns: Sequence[int],
    similarity_max_distances: Sequence[float],
    llm_models: Sequence[ModelSpec],
    client: GmsEmbeddingClient,
    cross_encoder: CrossEncoderReranker,
    gms_llm: GmsLlmReranker,
    store: VectorStore,
    booth_id: int = 1,
    agent_id: int = 1,
) -> dict[str, Any]:
    if overlap_ratio < 0 or overlap_ratio >= 1:
        raise ValueError("overlap ratio는 0 이상 1 미만이어야 합니다.")
    pages = load_document_pages(pdf_path)
    cases = load_eval_cases(eval_path)
    codec = TikTokenCodec()
    overlap = round(chunk_size * overlap_ratio)
    chunks = chunk_pages(pages, chunk_size=chunk_size, overlap=overlap, codec=codec)

    query_batch = client.embed([case.query for case in cases], model)
    chunk_batch = client.embed([chunk.content for chunk in chunks], model)
    run_id = uuid.uuid4().hex
    store.replace(
        run_id=run_id,
        model_id=model.model_id,
        booth_id=booth_id,
        agent_id=agent_id,
        chunks=chunks,
        vectors=chunk_batch.vectors,
    )

    started = time.perf_counter()
    results: list[dict[str, Any]] = []
    for top_n in top_ns:
        common = dict(
            store=store,
            run_id=run_id,
            model_id=model.model_id,
            booth_id=booth_id,
            agent_id=agent_id,
            cases=cases,
            query_vectors=query_batch.vectors,
            retrieval_top_k=retrieval_top_k,
            top_n=top_n,
        )
        results.append(evaluate_rerank_method(method=RerankMethod.NONE, **common))
        for max_distance in similarity_max_distances:
            row = evaluate_rerank_method(
                method=RerankMethod.SIMILARITY_CUTOFF,
                similarity_max_distance=max_distance,
                **common,
            )
            results.append({**row, "similarity_max_distance": max_distance})
        results.append(
            evaluate_rerank_method(
                method=RerankMethod.CROSS_ENCODER, cross_encoder=cross_encoder, **common
            )
        )
        for llm_model in llm_models:
            row = evaluate_rerank_method(
                method=RerankMethod.GMS_LLM,
                gms_llm=gms_llm,
                llm_model=llm_model,
                **common,
            )
            results.append({**row, "llm_model_id": llm_model.model_id})

    return {
        "schema_version": 1,
        "source_pdf_name": pdf_path.name,
        "embedding_model": model.model_id,
        "chunk_size": chunk_size,
        "overlap_ratio": overlap_ratio,
        "retrieval_top_k": retrieval_top_k,
        "chunk_count": len(chunks),
        "evaluation_case_count": len(cases),
        "query_embedding_seconds": query_batch.elapsed_seconds,
        "document_embedding_seconds": chunk_batch.elapsed_seconds,
        "comparison_elapsed_seconds": time.perf_counter() - started,
        "results": results,
    }
