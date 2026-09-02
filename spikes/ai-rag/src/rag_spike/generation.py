"""검색 컨텍스트+질문을 후보 LLM에 넣어 NPC 답변을 생성한다."""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from .gms_chat import GmsChatClient
from .models import ModelSpec, SearchHit


SAFETY_INSTRUCTION = (
    "제공된 context에 있는 내용만 근거로 한국어로 답한다. context에 없는 내용은 "
    '지어내지 말고, 답을 찾을 수 없으면 "문서에서 확인할 수 없습니다."라고만 답한다. '
    "개인정보를 요구하거나 제공하지 않는다."
)

# docs/08_Backend_API_명세서.md §6 "설정 허용값" 화이트리스트(2026-08-27 확정, spec 007 C-12)
ROLE_TEXT = {
    "PROJECT_DOCENT": "당신은 전시 프로젝트를 해설하는 AI 직원이다.",
    "GUIDE": "당신은 부스 운영과 이용 방법을 안내하는 AI 직원이다.",
}
TONE_TEXT = {
    "FRIENDLY": "친근한 존댓말로 답한다.",
    "PROFESSIONAL": "격식체로 정확하고 중립적으로 답한다.",
    "ENTHUSIASTIC": "활기찬 어조로 답한다.",
}
RESPONSE_LENGTH_TEXT = {
    "SHORT": "답변은 1~3문장으로 짧게 한다.",
    "MEDIUM": "답변은 4~6문장으로 한다.",
    "LONG": "답변은 7~12문장으로 한다.",
}
# responseLength -> max_tokens 매핑: docs/08 §6 제안값(AI 파트 소유, 모델 확정 후 재검증 대상)
RESPONSE_LENGTH_MAX_TOKENS = {"SHORT": 200, "MEDIUM": 400, "LONG": 800}


@dataclass(frozen=True)
class AgentPreset:
    role: str
    tone: str
    response_length: str
    forbidden_topics: tuple[str, ...] = ()


DEFAULT_PRESET = AgentPreset(role="GUIDE", tone="FRIENDLY", response_length="MEDIUM")


def build_system_prompt(preset: AgentPreset) -> str:
    """docs/13_AI_시스템_설계서.md §11 순서: System Instruction + Role/Tone + Safety."""
    lines = [
        ROLE_TEXT[preset.role],
        TONE_TEXT[preset.tone],
        RESPONSE_LENGTH_TEXT[preset.response_length],
        SAFETY_INSTRUCTION,
    ]
    if preset.forbidden_topics:
        lines.append("다음 주제는 답변하지 않는다: " + ", ".join(preset.forbidden_topics))
    return " ".join(lines)


def max_tokens_for(preset: AgentPreset) -> int:
    return RESPONSE_LENGTH_MAX_TOKENS[preset.response_length]


@dataclass(frozen=True)
class GoldCase:
    case_id: str
    query: str
    answer_status: str
    gold_answer: str
    relevant_contains: tuple[str, ...]


@dataclass(frozen=True)
class GenerationResult:
    model_id: str
    case_id: str
    query: str
    answer: str
    elapsed_seconds: float
    estimated_credits: float
    success: bool
    error: str | None = None


def load_gold_cases(path: Path) -> list[GoldCase]:
    cases: list[GoldCase] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            data = json.loads(line)
            case_id = str(data["id"]).strip()
            query = str(data["query"]).strip()
            if not case_id or not query:
                raise ValueError(f"{line_no}행의 id 또는 query가 비어 있습니다.")
            cases.append(
                GoldCase(
                    case_id=case_id,
                    query=query,
                    answer_status=str(data["answer_status"]),
                    gold_answer=str(data["answer"]),
                    relevant_contains=tuple(
                        str(value) for value in data.get("relevant_contains", [])
                    ),
                )
            )
    if not cases:
        raise ValueError("Gold 평가셋이 비어 있습니다.")
    return cases


def build_context(hits: Sequence[SearchHit]) -> str:
    if not hits:
        return "(검색된 문서 없음)"
    return "\n\n".join(f"[chunk {index}] {hit.content}" for index, hit in enumerate(hits, 1))


def generate_answer(
    *,
    client: GmsChatClient,
    model: ModelSpec,
    case_id: str,
    query: str,
    hits: Sequence[SearchHit],
    preset: AgentPreset = DEFAULT_PRESET,
) -> GenerationResult:
    context = build_context(hits)
    user_prompt = f"context:\n{context}\n\n질문: {query}"
    result = client.complete(
        system_prompt=build_system_prompt(preset),
        user_prompt=user_prompt,
        model=model,
        temperature=0.0,
        max_tokens=max_tokens_for(preset),
    )
    return GenerationResult(
        model_id=model.model_id,
        case_id=case_id,
        query=query,
        answer=result.text,
        elapsed_seconds=result.elapsed_seconds,
        estimated_credits=result.estimated_credits,
        success=result.success,
        error=result.error,
    )
