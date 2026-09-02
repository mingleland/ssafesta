"""모델·청킹·Top-K 조합을 실행하고 원문 없는 JSON 지표를 만든다."""

from __future__ import annotations

import json
import time
import uuid
from collections.abc import Sequence
from dataclasses import asdict
from pathlib import Path
from typing import Any

from .chunking import TikTokenCodec, chunk_pages
from .embedding import GmsEmbeddingClient
from .evaluation import evaluate_retrieval
from .models import EvalCase, ModelSpec
from .pdf_loader import load_document_pages
from .store import VectorStore


def load_eval_cases(path: Path) -> list[EvalCase]:
    cases: list[EvalCase] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            data = json.loads(line)
            case = EvalCase(
                case_id=str(data["id"]),
                query=str(data["query"]),
                relevant_pages=frozenset(int(page) for page in data.get("relevant_pages", [])),
                relevant_contains=tuple(str(value) for value in data.get("relevant_contains", [])),
            )
            if not case.relevant_pages and not case.relevant_contains:
                if data.get("answer_status") == "NO_ANSWER":
                    continue
                raise ValueError(f"{line_no}행 평가 케이스에 정답 기준이 없습니다.")
            cases.append(case)
    if not cases:
        raise ValueError("평가 JSONL에 질문이 없습니다.")
    return cases


def run_benchmark(
    *,
    pdf_path: Path,
    eval_path: Path,
    models: Sequence[ModelSpec],
    chunk_sizes: Sequence[int],
    overlap_ratios: Sequence[float],
    top_ks: Sequence[int],
    client: GmsEmbeddingClient,
    store: VectorStore,
    booth_id: int = 1,
    agent_id: int = 1,
) -> dict[str, Any]:
    pages = load_document_pages(pdf_path)
    cases = load_eval_cases(eval_path)
    excluded_no_answer_count = _count_no_answer(eval_path)
    codec = TikTokenCodec()
    results: list[dict[str, Any]] = []
    model_summaries: list[dict[str, Any]] = []
    for model in models:
        model_started = time.perf_counter()
        query_batch = client.embed([case.query for case in cases], model)
        model_request_count = query_batch.request_count
        for chunk_size in chunk_sizes:
            for ratio in overlap_ratios:
                if ratio < 0 or ratio >= 1:
                    raise ValueError("overlap ratio는 0 이상 1 미만이어야 합니다.")
                overlap = round(chunk_size * ratio)
                chunks = chunk_pages(
                    pages,
                    chunk_size=chunk_size,
                    overlap=overlap,
                    codec=codec,
                )
                chunk_batch = client.embed([chunk.content for chunk in chunks], model)
                model_request_count += chunk_batch.request_count
                run_id = uuid.uuid4().hex
                store.replace(
                    run_id=run_id,
                    model_id=model.model_id,
                    booth_id=booth_id,
                    agent_id=agent_id,
                    chunks=chunks,
                    vectors=chunk_batch.vectors,
                )
                for top_k in top_ks:
                    metrics = evaluate_retrieval(
                        store=store,
                        run_id=run_id,
                        model_id=model.model_id,
                        booth_id=booth_id,
                        agent_id=agent_id,
                        cases=cases,
                        query_vectors=query_batch.vectors,
                        top_k=top_k,
                    )
                    results.append(
                        {
                            "model_id": model.model_id,
                            "dimension": len(chunk_batch.vectors[0]),
                            "chunk_size": chunk_size,
                            "overlap": overlap,
                            "overlap_ratio": ratio,
                            "top_k": top_k,
                            "chunk_count": len(chunks),
                            "document_embedding_seconds": chunk_batch.elapsed_seconds,
                            **asdict(metrics),
                        }
                    )
        model_summaries.append(
            {
                "model_id": model.model_id,
                "query_embedding_seconds": query_batch.elapsed_seconds,
                "embedding_request_count": model_request_count,
                "estimated_gms_credits": model_request_count * model.credit_per_request,
                "credit_assumption": "모델 카드 표시값 × HTTP 요청 수",
                "elapsed_seconds": time.perf_counter() - model_started,
            }
        )
    return {
        "schema_version": 1,
        "source_pdf_name": pdf_path.name,
        "page_count": len(pages),
        "evaluation_case_count": len(cases),
        "excluded_no_answer_count": excluded_no_answer_count,
        "booth_id": booth_id,
        "agent_id": agent_id,
        "models": model_summaries,
        "results": results,
    }


def _count_no_answer(path: Path) -> int:
    count = 0
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            if line.strip() and json.loads(line).get("answer_status") == "NO_ANSWER":
                count += 1
    return count
