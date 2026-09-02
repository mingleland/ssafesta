"""정답 라벨이 없는 질문의 모델별 Top-K 결과와 검토 후보를 만든다."""

from __future__ import annotations

import json
import re
import time
import uuid
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from .chunking import TikTokenCodec, chunk_pages
from .embedding import GmsEmbeddingClient
from .models import ModelSpec, SearchHit
from .pdf_loader import load_document_pages
from .store import VectorStore


def load_queries(path: Path) -> list[dict[str, str]]:
    queries: list[dict[str, str]] = []
    seen: set[str] = set()
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            data = json.loads(line)
            query_id = str(data["id"]).strip()
            query = str(data["query"]).strip()
            if not query_id or not query:
                raise ValueError(f"{line_no}행의 id 또는 query가 비어 있습니다.")
            if query_id in seen:
                raise ValueError(f"중복 질문 id: {query_id}")
            seen.add(query_id)
            queries.append({"id": query_id, "query": query})
    if not queries:
        raise ValueError("질문 JSONL이 비어 있습니다.")
    return queries


def run_manual_review(
    *,
    document_path: Path,
    query_path: Path,
    models: Sequence[ModelSpec],
    client: GmsEmbeddingClient,
    store: VectorStore,
    chunk_size: int,
    overlap_ratio: float,
    top_k: int,
    booth_id: int = 1,
    agent_id: int = 1,
) -> dict[str, Any]:
    if not 0 <= overlap_ratio < 1:
        raise ValueError("overlap_ratio는 0 이상 1 미만이어야 합니다.")
    if top_k <= 0:
        raise ValueError("top_k는 1 이상이어야 합니다.")
    pages = load_document_pages(document_path)
    queries = load_queries(query_path)
    chunks = chunk_pages(
        pages,
        chunk_size=chunk_size,
        overlap=round(chunk_size * overlap_ratio),
        codec=TikTokenCodec(),
    )
    by_query: dict[str, dict[str, Any]] = {
        row["id"]: {
            "id": row["id"],
            "query": row["query"],
            "suggested_relevant_contains": [],
            "review_status": "PENDING",
            "model_results": {},
        }
        for row in queries
    }
    summaries: list[dict[str, Any]] = []
    for model in models:
        started = time.perf_counter()
        chunk_batch = client.embed([chunk.content for chunk in chunks], model)
        query_batch = client.embed([row["query"] for row in queries], model)
        run_id = uuid.uuid4().hex
        store.replace(
            run_id=run_id,
            model_id=model.model_id,
            booth_id=booth_id,
            agent_id=agent_id,
            chunks=chunks,
            vectors=chunk_batch.vectors,
        )
        search_started = time.perf_counter()
        for row, vector in zip(queries, query_batch.vectors, strict=True):
            hits = store.search(
                run_id=run_id,
                model_id=model.model_id,
                booth_id=booth_id,
                agent_id=agent_id,
                query_vector=vector,
                top_k=top_k,
            )
            by_query[row["id"]]["model_results"][model.model_id] = [
                _hit_for_review(hit, rank) for rank, hit in enumerate(hits, 1)
            ]
        search_seconds = time.perf_counter() - search_started
        request_count = chunk_batch.request_count + query_batch.request_count
        summaries.append(
            {
                "model_id": model.model_id,
                "document_embedding_seconds": chunk_batch.elapsed_seconds,
                "query_embedding_seconds": query_batch.elapsed_seconds,
                "search_seconds": search_seconds,
                "request_count": request_count,
                "estimated_gms_credits": request_count * model.credit_per_request,
                "elapsed_seconds": time.perf_counter() - started,
            }
        )
    for item in by_query.values():
        first_hits = [hits[0] for hits in item["model_results"].values() if hits]
        item["suggested_relevant_contains"] = _candidate_snippets(
            first_hits, str(item["query"])
        )
    return {
        "schema_version": 1,
        "mode": "manual_review",
        "document": document_path.name,
        "query_count": len(queries),
        "chunk_size": chunk_size,
        "overlap_ratio": overlap_ratio,
        "top_k": top_k,
        "chunk_count": len(chunks),
        "models": summaries,
        "questions": list(by_query.values()),
    }


def _hit_for_review(hit: SearchHit, rank: int) -> dict[str, Any]:
    return {
        "rank": rank,
        "chunk_id": hit.chunk_id,
        "page": hit.page,
        "distance": hit.distance,
        "content": hit.content,
    }


def _candidate_snippets(
    first_hits: Sequence[dict[str, Any]], query: str = ""
) -> list[str]:
    candidates: list[str] = []
    for hit in first_hits:
        content = str(hit["content"])
        parts = [
            part.strip(" -|\t")
            for part in re.split(r"[\r\n]+|(?<=[.!?다요])\s+", content)
        ]
        usable = [
            part
            for part in parts
            if 12 <= len(part) <= 300
            and not part.startswith("#")
            and not re.fullmatch(r"[:|\- ]+", part)
        ]
        snippet = max(
            usable,
            key=lambda part: (_lexical_score(query, part), -abs(len(part) - 90)),
            default=content[:180].strip(),
        )[:180]
        if snippet and snippet not in candidates:
            candidates.append(snippet)
    return candidates


def _lexical_score(query: str, candidate: str) -> int:
    ignored = {
        "어떤", "어떻게", "있나요", "되나요", "무엇인가요", "해주세요",
        "사람", "다른", "대한", "함께", "있는", "인가요",
    }
    terms = {
        token.lower()
        for token in re.findall(r"[가-힣A-Za-z0-9+]+", query)
        if len(token) >= 2 and token not in ignored
    }
    lowered = candidate.lower()
    return sum(3 if term in lowered else 0 for term in terms)


def refresh_review_report(report: dict[str, Any]) -> dict[str, Any]:
    for item in report.get("questions", []):
        first_hits = [
            hits[0]
            for hits in item.get("model_results", {}).values()
            if hits
        ]
        item["suggested_relevant_contains"] = _candidate_snippets(
            first_hits, str(item.get("query", ""))
        )
    return report


def review_jsonl(report: dict[str, Any]) -> str:
    rows = []
    for item in report.get("questions", []):
        model_top1 = {}
        for model_id, hits in item.get("model_results", {}).items():
            if not hits:
                continue
            hit = hits[0]
            model_top1[model_id] = {
                "chunk_id": hit["chunk_id"],
                "distance": hit["distance"],
                "candidate": _candidate_snippets([hit], str(item["query"]))[0],
            }
        rows.append(
            json.dumps(
                {
                    "id": item["id"],
                    "query": item["query"],
                    "relevant_contains": item["suggested_relevant_contains"],
                    "model_top1": model_top1,
                    "review_status": item.get("review_status", "PENDING"),
                },
                ensure_ascii=False,
            )
        )
    return "\n".join(rows) + "\n"
