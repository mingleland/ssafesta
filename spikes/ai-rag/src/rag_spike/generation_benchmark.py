"""370·371에서 확정된 검색 파이프라인 위에서 답변 생성 LLM 후보를 비교한다."""

from __future__ import annotations

import time
import uuid
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from .chunking import TikTokenCodec, chunk_pages
from .embedding import GmsEmbeddingClient
from .generation import DEFAULT_PRESET, AgentPreset, generate_answer, load_gold_cases
from .gms_chat import GmsChatClient
from .models import ModelSpec
from .pdf_loader import load_document_pages
from .store import VectorStore


def run_generation_benchmark(
    *,
    pdf_path: Path,
    gold_path: Path,
    embedding_model: ModelSpec,
    chunk_size: int,
    overlap_ratio: float,
    top_n: int,
    generation_models: Sequence[ModelSpec],
    embedding_client: GmsEmbeddingClient,
    chat_client: GmsChatClient,
    store: VectorStore,
    booth_id: int = 1,
    agent_id: int = 1,
    preset: AgentPreset = DEFAULT_PRESET,
) -> dict[str, Any]:
    if overlap_ratio < 0 or overlap_ratio >= 1:
        raise ValueError("overlap ratio는 0 이상 1 미만이어야 합니다.")
    pages = load_document_pages(pdf_path)
    gold_cases = load_gold_cases(gold_path)
    codec = TikTokenCodec()
    overlap = round(chunk_size * overlap_ratio)
    chunks = chunk_pages(pages, chunk_size=chunk_size, overlap=overlap, codec=codec)

    chunk_batch = embedding_client.embed([chunk.content for chunk in chunks], embedding_model)
    query_batch = embedding_client.embed([case.query for case in gold_cases], embedding_model)
    run_id = uuid.uuid4().hex
    store.replace(
        run_id=run_id,
        model_id=embedding_model.model_id,
        booth_id=booth_id,
        agent_id=agent_id,
        chunks=chunks,
        vectors=chunk_batch.vectors,
    )

    hits_by_case = {
        case.case_id: store.search(
            run_id=run_id,
            model_id=embedding_model.model_id,
            booth_id=booth_id,
            agent_id=agent_id,
            query_vector=vector,
            top_k=top_n,
        )
        for case, vector in zip(gold_cases, query_batch.vectors, strict=True)
    }

    questions: dict[str, dict[str, Any]] = {
        case.case_id: {
            "case_id": case.case_id,
            "query": case.query,
            "gold_answer_status": case.answer_status,
            "gold_answer": case.gold_answer,
            "model_answers": {},
        }
        for case in gold_cases
    }
    model_summaries: list[dict[str, Any]] = []
    for model in generation_models:
        started = time.perf_counter()
        request_count = 0
        failure_count = 0
        for case in gold_cases:
            result = generate_answer(
                client=chat_client,
                model=model,
                case_id=case.case_id,
                query=case.query,
                hits=hits_by_case[case.case_id],
                preset=preset,
            )
            request_count += 1
            if not result.success:
                failure_count += 1
            questions[case.case_id]["model_answers"][model.model_id] = {
                "answer": result.answer,
                "elapsed_seconds": result.elapsed_seconds,
                "estimated_credits": result.estimated_credits,
                "success": result.success,
                "error": result.error,
            }
        model_summaries.append(
            {
                "model_id": model.model_id,
                "request_count": request_count,
                "estimated_credits": request_count * model.credit_per_request,
                "elapsed_seconds": time.perf_counter() - started,
                "failure_count": failure_count,
            }
        )

    return {
        "schema_version": 1,
        "document": pdf_path.name,
        "case_count": len(gold_cases),
        "embedding_model": embedding_model.model_id,
        "chunk_size": chunk_size,
        "overlap_ratio": overlap_ratio,
        "top_n": top_n,
        "agent_preset": {
            "role": preset.role,
            "tone": preset.tone,
            "response_length": preset.response_length,
        },
        "models": model_summaries,
        "questions": list(questions.values()),
        "limitations": [
            "TTFT는 측정하지 않았다 — 스트리밍 미사용, 전체 응답시간만 기록. "
            "실사용 시 스트리밍 적용 후 별도 실측 필요.",
        ],
    }
