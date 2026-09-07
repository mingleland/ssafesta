"""여러 문서의 검색 평가 결과를 같은 조건으로 가중 집계한다."""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


QUALITY_RECALL_THRESHOLD = 0.95
SEARCH_P95_LIMIT_MS = 1000.0


def load_reports(paths: Iterable[Path]) -> list[dict[str, Any]]:
    reports = [json.loads(path.read_text(encoding="utf-8")) for path in paths]
    if not reports:
        raise ValueError("집계할 평가 결과가 없습니다.")
    return reports


def aggregate_reports(reports: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[tuple[str, int, float, int], list[dict[str, Any]]] = defaultdict(list)
    model_runs: dict[str, list[dict[str, Any]]] = defaultdict(list)
    expected_documents = {str(report["source_pdf_name"]) for report in reports}
    for report in reports:
        document = str(report["source_pdf_name"])
        for model in report.get("models", []):
            model_runs[str(model["model_id"])].append(model)
        for row in report["results"]:
            grouped[_key(row)].append({"document": document, **row})

    candidates: list[dict[str, Any]] = []
    for key, rows in grouped.items():
        documents = {row["document"] for row in rows}
        if documents != expected_documents:
            missing = ", ".join(sorted(expected_documents - documents))
            raise ValueError(f"{key} 조합에 문서 결과가 누락되었습니다: {missing}")
        total_queries = sum(int(row["evaluated_queries"]) for row in rows)
        candidate = {
            "model_id": key[0],
            "chunk_size": key[1],
            "overlap_ratio": key[2],
            "top_k": key[3],
            "evaluated_queries": total_queries,
            "weighted_recall": _weighted(rows, "recall_at_k", total_queries),
            "weighted_mrr": _weighted(rows, "mrr", total_queries),
            "worst_document_recall": min(float(row["recall_at_k"]) for row in rows),
            "max_p50_search_ms": max(float(row["p50_search_ms"]) for row in rows),
            "max_p95_search_ms": max(float(row["p95_search_ms"]) for row in rows),
            "weighted_mean_context_tokens": _weighted(
                rows, "mean_context_tokens", total_queries
            ),
            "max_p95_context_tokens": max(float(row["p95_context_tokens"]) for row in rows),
            "leakage_count": sum(int(row["leakage_count"]) for row in rows),
            "documents": [
                {
                    "document": row["document"],
                    "recall": row["recall_at_k"],
                    "mrr": row["mrr"],
                    "missed_case_ids": row["missed_case_ids"],
                }
                for row in sorted(rows, key=lambda item: item["document"])
            ],
        }
        candidate["quality_gate_passed"] = (
            candidate["weighted_recall"] >= QUALITY_RECALL_THRESHOLD
            and candidate["max_p95_search_ms"] <= SEARCH_P95_LIMIT_MS
            and candidate["leakage_count"] == 0
        )
        candidates.append(candidate)

    candidates.sort(key=_candidate_order)
    best_by_model: dict[str, dict[str, Any]] = {}
    for candidate in candidates:
        best_by_model.setdefault(candidate["model_id"], candidate)
    passed = [candidate for candidate in candidates if candidate["quality_gate_passed"]]
    return {
        "schema_version": 1,
        "document_count": len(expected_documents),
        "documents": sorted(expected_documents),
        "decision_policy": {
            "minimum_weighted_recall": QUALITY_RECALL_THRESHOLD,
            "maximum_search_p95_ms": SEARCH_P95_LIMIT_MS,
            "tie_break_order": [
                "quality gate",
                "weighted recall desc",
                "weighted MRR desc",
                "weighted mean context tokens asc",
                "search P95 asc",
            ],
        },
        "quality_gate_candidate_count": len(passed),
        "model_execution_summaries": [
            {
                "model_id": model_id,
                "embedding_request_count": sum(
                    int(run["embedding_request_count"]) for run in runs
                ),
                "estimated_gms_credits": sum(
                    float(run["estimated_gms_credits"]) for run in runs
                ),
                "elapsed_seconds": sum(float(run["elapsed_seconds"]) for run in runs),
            }
            for model_id, runs in sorted(model_runs.items())
        ],
        "recommended": passed[0] if passed else (candidates[0] if candidates else None),
        "best_by_model": list(best_by_model.values()),
        "candidates": candidates,
    }


def render_markdown(summary: dict[str, Any], *, limit_per_model: int = 10) -> str:
    recommended = summary["recommended"]
    lines = [
        "# 청킹·Overlap·Top-K 튜닝 결과",
        "",
        "## 평가 기준",
        "",
        f"- 문서: {', '.join(summary['documents'])}",
        "- 1차: 전체 질문 가중 Recall 0.95 이상 및 검색 P95 1,000ms 이하",
        "- 2차: MRR이 높은 조합",
        "- 3차: LLM 입력 예상 토큰과 검색 지연이 낮은 조합",
        "- 격리 위반은 0건이어야 함",
        "",
        "## 임베딩 실행 비용",
        "",
        "| 모델 | HTTP 요청 | 추정 Credit | 세 문서 전체 처리 |",
        "|---|---:|---:|---:|",
        *[
            f"| {model['model_id']} | {model['embedding_request_count']} | "
            f"{model['estimated_gms_credits']:.3f} | {model['elapsed_seconds']:.1f}초 |"
            for model in summary["model_execution_summaries"]
        ],
        "",
        "## 권장 기본값",
        "",
    ]
    if recommended is None:
        lines.append("평가 결과가 없습니다.")
    else:
        gate = "통과" if recommended["quality_gate_passed"] else "미통과(가용 조합 중 최고)"
        lines.extend(
            [
                f"- 모델: `{recommended['model_id']}`",
                f"- Chunk / Overlap / Top-K: `{recommended['chunk_size']}` / "
                f"`{recommended['overlap_ratio']:.0%}` / `{recommended['top_k']}`",
                f"- Recall / MRR: `{recommended['weighted_recall']:.4f}` / "
                f"`{recommended['weighted_mrr']:.4f}`",
                f"- 예상 Context: 평균 `{recommended['weighted_mean_context_tokens']:.1f}` tokens",
                f"- 품질 게이트: **{gate}**",
            ]
        )

    by_model: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for candidate in summary["candidates"]:
        by_model[candidate["model_id"]].append(candidate)
    for model_id, candidates in sorted(by_model.items()):
        lines.extend(
            [
                "",
                f"## {model_id} 상위 조합",
                "",
                "| Chunk | Overlap | Top-K | Recall | MRR | 최저 문서 Recall | 평균 Context tokens | P95 ms | Gate |",
                "|---:|---:|---:|---:|---:|---:|---:|---:|:---:|",
            ]
        )
        for candidate in candidates[:limit_per_model]:
            lines.append(
                f"| {candidate['chunk_size']} | {candidate['overlap_ratio']:.0%} | "
                f"{candidate['top_k']} | {candidate['weighted_recall']:.4f} | "
                f"{candidate['weighted_mrr']:.4f} | {candidate['worst_document_recall']:.4f} | "
                f"{candidate['weighted_mean_context_tokens']:.1f} | "
                f"{candidate['max_p95_search_ms']:.2f} | "
                f"{'PASS' if candidate['quality_gate_passed'] else 'FAIL'} |"
            )

    lines.extend(["", "## 문서별 실패 질문", ""])
    if recommended is not None:
        for document in recommended["documents"]:
            missed = ", ".join(f"`{case_id}`" for case_id in document["missed_case_ids"])
            lines.append(f"- **{document['document']}**: {missed or '없음'}")
    return "\n".join(lines) + "\n"


def _key(row: dict[str, Any]) -> tuple[str, int, float, int]:
    return (
        str(row["model_id"]),
        int(row["chunk_size"]),
        float(row["overlap_ratio"]),
        int(row["top_k"]),
    )


def _weighted(rows: list[dict[str, Any]], field: str, total_queries: int) -> float:
    return sum(float(row[field]) * int(row["evaluated_queries"]) for row in rows) / total_queries


def _candidate_order(candidate: dict[str, Any]) -> tuple[Any, ...]:
    return (
        not candidate["quality_gate_passed"],
        -candidate["weighted_recall"],
        -candidate["weighted_mrr"],
        candidate["weighted_mean_context_tokens"],
        candidate["max_p95_search_ms"],
        candidate["top_k"],
    )
