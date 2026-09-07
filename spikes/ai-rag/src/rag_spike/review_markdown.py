"""임베딩 수동 검토 JSON을 사람이 읽기 쉬운 Markdown으로 변환한다."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def render_review_markdown(report: dict[str, Any], title: str) -> str:
    lines = [
        f"# {title} 임베딩 검색 결과 검토",
        "",
        "> 각 질문에서 정답 근거로 사용할 문장을 선택하세요. 문서에 답이 없으면 "
        "`NO_ANSWER`를 선택합니다.",
        "",
        "## 실행 조건",
        "",
        f"- 문서: `{report['document']}`",
        f"- 질문: {report['query_count']}개",
        f"- 청크: {report['chunk_count']}개 / {report['chunk_size']} tokens",
        f"- overlap: {report['overlap_ratio']}",
        f"- Top-K: {report['top_k']}",
        "",
        "## 모델 처리 결과",
        "",
        "| 모델 | 문서 임베딩 | 질문 임베딩 | 검색 | 요청 | 추정 Credit | 전체 |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for model in report["models"]:
        lines.append(
            f"| {model['model_id']} | {model['document_embedding_seconds']:.3f}s | "
            f"{model['query_embedding_seconds']:.3f}s | {model['search_seconds']:.3f}s | "
            f"{model['request_count']} | {model['estimated_gms_credits']:.3f} | "
            f"{model['elapsed_seconds']:.3f}s |"
        )
    lines.extend(["", "---", ""])
    for index, item in enumerate(report["questions"], 1):
        lines.extend(_render_question(index, item))
    return "\n".join(lines).rstrip() + "\n"


def render_file(input_path: Path, output_path: Path, title: str) -> None:
    report = json.loads(input_path.read_text(encoding="utf-8"))
    output_path.write_text(render_review_markdown(report, title), encoding="utf-8")


def render_gold_file(input_path: Path, output_path: Path, title: str) -> None:
    rows = [json.loads(line) for line in input_path.read_text(encoding="utf-8").splitlines() if line]
    counts = {
        status: sum(row["answer_status"] == status for row in rows)
        for status in ("ANSWERABLE", "PARTIAL", "NO_ANSWER")
    }
    lines = [
        f"# {title} Sol 정답 청크 제안",
        "",
        "> `gpt-5.6-sol`이 임베딩 검색 결과를 보지 않고 원문만 대조해 만든 골드 라벨 후보입니다.",
        "",
        f"- 전체: {len(rows)}개",
        f"- ANSWERABLE: {counts['ANSWERABLE']}개",
        f"- PARTIAL: {counts['PARTIAL']}개",
        f"- NO_ANSWER: {counts['NO_ANSWER']}개",
        "",
    ]
    for index, row in enumerate(rows, 1):
        lines.extend(
            [
                f"## {index}. {row['query']}",
                "",
                f"- 판정: **{row['answer_status']}**",
                f"- 제안 답변: {row['answer']}",
                f"- 판정 이유: {row['rationale']}",
                "- 정답 청크:",
            ]
        )
        if row["relevant_contains"]:
            lines.extend(f"  - `{value}`" for value in row["relevant_contains"])
        else:
            lines.append("  - 없음")
        lines.extend(["", "---", ""])
    output_path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def _render_question(index: int, item: dict[str, Any]) -> list[str]:
    lines = [
        f"## {index}. {item['query']}",
        "",
        "### 모델별 Top-1",
        "",
        "| 모델 | 청크 | 거리 | 정답 후보 |",
        "|---|---|---:|---|",
    ]
    for model_id, hits in item["model_results"].items():
        if not hits:
            lines.append(f"| {model_id} | - | - | 검색 결과 없음 |")
            continue
        hit = hits[0]
        candidate = _candidate_for_hit(item, model_id).replace("|", "\\|")
        lines.append(
            f"| {model_id} | `{hit['chunk_id']}` | {hit['distance']:.4f} | {candidate} |"
        )
    lines.extend(
        [
            "",
            "### 모델이 제안한 relevant_contains",
            "",
        ]
    )
    for candidate in item.get("suggested_relevant_contains", []):
        lines.append(f"- [ ] `{candidate}`")
    lines.extend(
        [
            "- [ ] 문서에 답이 없음 (`NO_ANSWER`)",
            "",
            "### 최종 판정 — 직접 작성",
            "",
            "- 상태: `PENDING`",
            "- 최종 relevant_contains:",
            "  - `선택한 정답 문장을 여기에 붙여 넣기`",
            "",
            "<details>",
            "<summary>모델별 Top-3 청크 발췌 보기</summary>",
            "",
        ]
    )
    for model_id, hits in item["model_results"].items():
        lines.extend([f"#### {model_id}", ""])
        for hit in hits:
            content = _excerpt(
                str(hit["content"]).replace("\r", "").strip(),
                _candidate_for_hit({**item, "model_results": {model_id: [hit]}}, model_id),
            )
            lines.extend(
                [
                    f"**{hit['rank']}위 · `{hit['chunk_id']}` · 거리 {hit['distance']:.4f}**",
                    "",
                    "```text",
                    content,
                    "```",
                    "",
                ]
            )
    lines.extend(["</details>", "", "---", ""])
    return lines


def _candidate_for_hit(item: dict[str, Any], model_id: str) -> str:
    from .manual_review import _candidate_snippets

    hit = item["model_results"][model_id][0]
    candidates = _candidate_snippets([hit], str(item["query"]))
    return candidates[0] if candidates else "후보 없음"


def _excerpt(content: str, candidate: str, limit: int = 300) -> str:
    if len(content) <= limit:
        return content
    position = content.find(candidate)
    if position < 0:
        return content[:limit].rstrip() + "…"
    start = max(0, position - limit // 3)
    end = min(len(content), start + limit)
    prefix = "…" if start > 0 else ""
    suffix = "…" if end < len(content) else ""
    return prefix + content[start:end].strip() + suffix
