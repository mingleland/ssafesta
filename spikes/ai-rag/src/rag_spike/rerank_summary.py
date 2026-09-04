"""세 문서의 reranker 비교 결과를 하나의 의사결정 문서로 집계한다."""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


def load_reports(paths: Iterable[Path]) -> list[dict[str, Any]]:
    reports = [json.loads(path.read_text(encoding="utf-8")) for path in paths]
    if not reports:
        raise ValueError("집계할 평가 결과가 없습니다.")
    return reports


def aggregate_reports(reports: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[tuple[str, int, str], list[dict[str, Any]]] = defaultdict(list)
    expected_documents = {str(report["source_pdf_name"]) for report in reports}
    for report in reports:
        document = str(report["source_pdf_name"])
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
            "method": key[0],
            "top_n": key[1],
            "variant": key[2],
            "evaluated_queries": total_queries,
            "weighted_recall": _weighted(rows, "recall_at_n", total_queries),
            "weighted_mrr": _weighted(rows, "mrr", total_queries),
            "weighted_ndcg": _weighted(rows, "ndcg", total_queries),
            "worst_document_recall": min(float(row["recall_at_n"]) for row in rows),
            "weighted_mean_context_tokens": _weighted(
                rows, "mean_context_tokens", total_queries
            ),
            "total_rerank_elapsed_seconds": sum(
                float(row["rerank_elapsed_seconds"]) for row in rows
            ),
            "total_rerank_request_count": sum(
                int(row["rerank_request_count"]) for row in rows
            ),
            "total_estimated_rerank_credits": sum(
                float(row["estimated_rerank_credits"]) for row in rows
            ),
            "total_rerank_failure_count": sum(
                int(row["rerank_failure_count"]) for row in rows
            ),
            "documents": [
                {
                    "document": row["document"],
                    "recall": row["recall_at_n"],
                    "mrr": row["mrr"],
                    "ndcg": row["ndcg"],
                    "missed_case_ids": row["missed_case_ids"],
                }
                for row in sorted(rows, key=lambda item: item["document"])
            ],
        }
        candidates.append(candidate)

    candidates.sort(key=_candidate_order)
    return {
        "schema_version": 1,
        "document_count": len(expected_documents),
        "documents": sorted(expected_documents),
        "decision_policy": {
            "tie_break_order": [
                "weighted recall desc",
                "weighted NDCG desc",
                "weighted MRR desc",
                "reranker 실패 건수 asc",
                "reranker 추가 지연시간 asc",
                "reranker 추정 Credit asc",
            ],
        },
        "recommended": candidates[0] if candidates else None,
        "candidates": candidates,
    }


def render_markdown(summary: dict[str, Any]) -> str:
    recommended = summary["recommended"]
    lines = [
        "# Reranker 비교 결과",
        "",
        "## 평가 기준",
        "",
        f"- 문서: {', '.join(summary['documents'])}",
        "- 정렬: 가중 Recall desc → 가중 NDCG desc → 가중 MRR desc → "
        "실패 건수 asc → 추가 지연 asc → Credit asc",
        "",
        "## 권장 방식",
        "",
    ]
    if recommended is None:
        lines.append("평가 결과가 없습니다.")
    else:
        variant_suffix = f" ({recommended['variant']})" if recommended["variant"] else ""
        lines.extend(
            [
                f"- 방식: `{recommended['method']}`{variant_suffix}",
                f"- Top-N: `{recommended['top_n']}`",
                f"- Recall / MRR / NDCG: `{recommended['weighted_recall']:.4f}` / "
                f"`{recommended['weighted_mrr']:.4f}` / `{recommended['weighted_ndcg']:.4f}`",
                f"- 추가 지연: `{recommended['total_rerank_elapsed_seconds']:.3f}`초, "
                f"추정 Credit: `{recommended['total_estimated_rerank_credits']:.3f}`",
                f"- 재정렬 실패 건수: `{recommended['total_rerank_failure_count']}`",
            ]
        )

    lines.extend(
        [
            "",
            "## 전체 후보",
            "",
            "| 방식 | Top-N | 조건 | Recall | MRR | NDCG | 실패 | 지연(초) | Credit |",
            "|---|---:|---|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for candidate in summary["candidates"]:
        lines.append(
            f"| {candidate['method']} | {candidate['top_n']} | {candidate['variant'] or '-'} | "
            f"{candidate['weighted_recall']:.4f} | {candidate['weighted_mrr']:.4f} | "
            f"{candidate['weighted_ndcg']:.4f} | {candidate['total_rerank_failure_count']} | "
            f"{candidate['total_rerank_elapsed_seconds']:.3f} | "
            f"{candidate['total_estimated_rerank_credits']:.3f} |"
        )

    lines.extend(["", "## 문서별 실패 질문", ""])
    if recommended is not None:
        for document in recommended["documents"]:
            missed = ", ".join(f"`{case_id}`" for case_id in document["missed_case_ids"])
            lines.append(f"- **{document['document']}**: {missed or '없음'}")
    return "\n".join(lines) + "\n"


def _key(row: dict[str, Any]) -> tuple[str, int, str]:
    return (str(row["method"]), int(row["top_n"]), _variant(row))


def _variant(row: dict[str, Any]) -> str:
    if "similarity_max_distance" in row:
        return f"threshold={row['similarity_max_distance']}"
    if "llm_model_id" in row:
        return str(row["llm_model_id"])
    return ""


def _weighted(rows: list[dict[str, Any]], field: str, total_queries: int) -> float:
    return sum(float(row[field]) * int(row["evaluated_queries"]) for row in rows) / total_queries


def _candidate_order(candidate: dict[str, Any]) -> tuple[Any, ...]:
    return (
        -candidate["weighted_recall"],
        -candidate["weighted_ndcg"],
        -candidate["weighted_mrr"],
        candidate["total_rerank_failure_count"],
        candidate["total_rerank_elapsed_seconds"],
        candidate["total_estimated_rerank_credits"],
    )
