"""LLM 답변 생성 비교 결과를 사람이 채점하기 쉬운 Markdown으로 변환한다."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


SCORING_AXES = (
    "Groundedness (context 근거 기반인가)",
    "Relevance (질문에 직접 답하는가)",
    "환각 없음 (context에 없는 내용을 지어내지 않았는가)",
    "거절 판단 적절성 (NO_ANSWER를 적절히 거절했는가)",
    "한국어 자연스러움·NPC 형식",
)


def render_generation_review(report: dict[str, Any], title: str) -> str:
    lines = [
        f"# {title} 답변 생성 LLM 비교 검토",
        "",
        "> 각 질문에서 모델별 답변을 Gold 정답과 대조해 아래 축마다 체크하세요. "
        "채점 후 `### 최종 판정`에 선택한 모델을 적으세요.",
        "",
        "## 실행 조건",
        "",
        f"- 문서: `{report['document']}`",
        f"- 질문: {report['case_count']}개",
        "",
        "## 모델 처리 결과",
        "",
        "| 모델 | 요청 | 추정 Credit | 전체 소요 | 실패 |",
        "|---|---:|---:|---:|---:|",
    ]
    for model in report["models"]:
        lines.append(
            f"| {model['model_id']} | {model['request_count']} | "
            f"{model['estimated_credits']:.3f} | {model['elapsed_seconds']:.3f}s | "
            f"{model['failure_count']} |"
        )
    lines.extend(["", "---", ""])
    for index, item in enumerate(report["questions"], 1):
        lines.extend(_render_question(index, item))
    return "\n".join(lines).rstrip() + "\n"


def render_file(input_path: Path, output_path: Path, title: str) -> None:
    report = json.loads(input_path.read_text(encoding="utf-8"))
    output_path.write_text(render_generation_review(report, title), encoding="utf-8")


def _render_question(index: int, item: dict[str, Any]) -> list[str]:
    lines = [
        f"## {index}. {item['query']}",
        "",
        f"- Gold 판정: **{item['gold_answer_status']}**",
        f"- Gold 정답: {item['gold_answer']}",
        "",
    ]
    for model_id, answer in item["model_answers"].items():
        status = "성공" if answer["success"] else f"**실패** ({answer['error']})"
        lines.extend(
            [
                f"### {model_id} — {status}",
                "",
                f"> {answer['answer'] or '(빈 응답)'}",
                "",
                f"- 지연: {answer['elapsed_seconds']:.3f}s / "
                f"추정 Credit: {answer['estimated_credits']:.3f}",
                "",
            ]
        )
        lines.extend(f"- [ ] {axis}" for axis in SCORING_AXES)
        lines.append("")
    lines.extend(
        [
            "### 최종 판정 — 직접 작성",
            "",
            "- 선택 모델: `미정`",
            "- 근거: ",
            "",
            "---",
            "",
        ]
    )
    return lines
