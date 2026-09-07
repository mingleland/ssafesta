"""여러 문서의 청킹 전략 비교 결과를 같은 조건으로 가중 집계한다."""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


QUALITY_RECALL_THRESHOLD = 0.95
SEARCH_P95_LIMIT_MS = 1000.0

STRATEGY_LABELS: dict[str, str] = {
    "fixed": "고정 토큰 (370 확정값, 대조군)",
    "structural": "구조적 (문단 경계)",
    "semantic": "의미적 (문장 임베딩 경계)",
    "parent_child": "Parent-Child (Child 검색·Parent 컨텍스트)",
}


def load_reports(paths: Iterable[Path]) -> list[dict[str, Any]]:
    reports = [json.loads(path.read_text(encoding="utf-8")) for path in paths]
    if not reports:
        raise ValueError("집계할 평가 결과가 없습니다.")
    return reports


def aggregate_reports(reports: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    expected_documents = {str(report["source_pdf_name"]) for report in reports}
    model_id = ""
    total_request_count = 0
    total_boundary_request_count = 0
    total_credits = 0.0
    for report in reports:
        document = str(report["source_pdf_name"])
        model = report["model"]
        model_id = str(model["model_id"])
        total_request_count += int(model["embedding_request_count"])
        total_boundary_request_count += int(model.get("boundary_embedding_request_count", 0))
        total_credits += float(model["estimated_gms_credits"])
        for row in report["results"]:
            grouped[str(row["strategy"])].append({"document": document, **row})

    candidates: list[dict[str, Any]] = []
    for strategy, rows in grouped.items():
        documents = {row["document"] for row in rows}
        if documents != expected_documents:
            missing = ", ".join(sorted(expected_documents - documents))
            raise ValueError(f"{strategy} 전략에 문서 결과가 누락되었습니다: {missing}")
        total_queries = sum(int(row["evaluated_queries"]) for row in rows)
        candidate = {
            "strategy": strategy,
            "strategy_label": STRATEGY_LABELS.get(strategy, strategy),
            "evaluated_queries": total_queries,
            "weighted_recall": _weighted(rows, "recall_at_k", total_queries),
            "weighted_mrr": _weighted(rows, "mrr", total_queries),
            "worst_document_recall": min(float(row["recall_at_k"]) for row in rows),
            "max_p95_search_ms": max(float(row["p95_search_ms"]) for row in rows),
            "weighted_mean_context_tokens": _weighted(
                rows, "mean_context_tokens", total_queries
            ),
            "max_p95_context_tokens": max(float(row["p95_context_tokens"]) for row in rows),
            "total_chunk_count": sum(int(row["chunk_count"]) for row in rows),
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
    passed = [candidate for candidate in candidates if candidate["quality_gate_passed"]]
    return {
        "schema_version": 1,
        "document_count": len(expected_documents),
        "documents": sorted(expected_documents),
        "model_id": model_id,
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
        "embedding_request_count": total_request_count,
        "boundary_embedding_request_count": total_boundary_request_count,
        "estimated_gms_credits": total_credits,
        "recommended": passed[0] if passed else (candidates[0] if candidates else None),
        "candidates": candidates,
    }


def render_markdown(summary: dict[str, Any]) -> str:
    recommended = summary["recommended"]
    lines = [
        "# 청킹 전략(구조·의미·Parent-Child) 비교 결과",
        "",
        "## 평가 기준",
        "",
        f"- 문서: {', '.join(summary['documents'])}",
        f"- 모델: `{summary['model_id']}` (370 확정값 고정)",
        "- 1차: 전체 질문 가중 Recall 0.95 이상 및 검색 P95 1,000ms 이하",
        "- 2차: MRR이 높은 전략",
        "- 3차: LLM 입력 예상 Context 토큰이 낮은 전략",
        "- 격리 위반은 0건이어야 함",
        "",
        "## 임베딩 실행 비용",
        "",
        f"- HTTP 요청: {summary['embedding_request_count']}건 "
        f"(의미 청킹 경계 탐지 {summary['boundary_embedding_request_count']}건 포함)",
        f"- 추정 Credit: {summary['estimated_gms_credits']:.3f}",
        "",
        "## 권장 전략",
        "",
    ]
    if recommended is None:
        lines.append("평가 결과가 없습니다.")
    else:
        gate = "통과" if recommended["quality_gate_passed"] else "미통과(가용 전략 중 최고)"
        lines.extend(
            [
                f"- 전략: `{recommended['strategy']}` ({recommended['strategy_label']})",
                f"- Recall / MRR: `{recommended['weighted_recall']:.4f}` / "
                f"`{recommended['weighted_mrr']:.4f}`",
                f"- 예상 Context: 평균 `{recommended['weighted_mean_context_tokens']:.1f}` tokens",
                f"- 총 청크 수: {recommended['total_chunk_count']}",
                f"- 품질 게이트: **{gate}**",
            ]
        )

    lines.extend(
        [
            "",
            "## 전략별 비교",
            "",
            "| 전략 | Recall | MRR | 최저 문서 Recall | 평균 Context tokens | 청크 수 | P95 ms | Gate |",
            "|---|---:|---:|---:|---:|---:|---:|:---:|",
        ]
    )
    for candidate in summary["candidates"]:
        lines.append(
            f"| {candidate['strategy_label']} | {candidate['weighted_recall']:.4f} | "
            f"{candidate['weighted_mrr']:.4f} | {candidate['worst_document_recall']:.4f} | "
            f"{candidate['weighted_mean_context_tokens']:.1f} | "
            f"{candidate['total_chunk_count']} | "
            f"{candidate['max_p95_search_ms']:.2f} | "
            f"{'PASS' if candidate['quality_gate_passed'] else 'FAIL'} |"
        )

    lines.extend(["", "## 전략별·문서별 실패 질문", ""])
    for candidate in summary["candidates"]:
        lines.append(f"### {candidate['strategy_label']}")
        for document in candidate["documents"]:
            missed = ", ".join(f"`{case_id}`" for case_id in document["missed_case_ids"])
            lines.append(f"- **{document['document']}**: {missed or '없음'}")
        lines.append("")
    return "\n".join(lines) + "\n"


def _weighted(rows: list[dict[str, Any]], field: str, total_queries: int) -> float:
    return sum(float(row[field]) * int(row["evaluated_queries"]) for row in rows) / total_queries


def _candidate_order(candidate: dict[str, Any]) -> tuple[Any, ...]:
    return (
        not candidate["quality_gate_passed"],
        -candidate["weighted_recall"],
        -candidate["weighted_mrr"],
        candidate["weighted_mean_context_tokens"],
        candidate["max_p95_search_ms"],
    )
