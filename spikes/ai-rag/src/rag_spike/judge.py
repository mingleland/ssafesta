"""LLM Judge로 답변 생성 비교 결과를 사람 채점 5축으로 자동 채점한다 (S15P21A604-372).

`generation_review_markdown.py`가 만드는 사람 채점용 markdown 템플릿을 대체할
수 있는 optional 자동화 경로다 — Jira 티켓이 "사람 평가 또는 LLM Judge"를
동등하게 인정하므로, 채점 축(AXIS_NAMES)은 그 템플릿의 SCORING_AXES와 1:1로
맞췄다. 실패는 조용히 삼키지 않고 ``JudgeVerdict.success=False``로 드러낸다
(gms_chat.py와 같은 원칙) — 파싱 실패·범위 밖 점수·Provider 오류 전부 실패로
취급하고 case×model 단위로만 건너뛴다.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from .gms_chat import GmsChatClient
from .models import ModelSpec

JUDGE_SYSTEM_PROMPT = """당신은 RAG 챗봇의 답변 품질을 채점하는 엄격한 평가자입니다.
질문, Gold 판정·정답(참고용), 채점 대상 답변을 보고 아래 5개 축을 각각 0(미흡)/1(보통)/2(우수)로 채점하세요.

- groundedness: 답변이 문서 근거에 기반했는가 (Gold 정답과 사실관계가 부합하는가)
- relevance: 질문에 직접 답했는가
- no_hallucination: 문서에 없는 내용을 지어내지 않았는가
- rejection_appropriate: Gold 판정이 NO_ANSWER인데 답변도 적절히 거절했는가, 또는 ANSWERABLE인데 근거 없이 거절하지 않았는가 (해당 없으면 2점)
- naturalness: 한국어 문장이 자연스럽고 챗봇 답변 형식에 맞는가

반드시 아래 JSON 형식으로만 답하세요. 다른 텍스트를 덧붙이지 마세요.
{"groundedness": 0-2, "relevance": 0-2, "no_hallucination": 0-2, "rejection_appropriate": 0-2, "naturalness": 0-2, "reasoning": "한두 문장 근거"}"""

AXIS_NAMES = (
    "groundedness",
    "relevance",
    "no_hallucination",
    "rejection_appropriate",
    "naturalness",
)


class JudgeParseError(Exception):
    """채점 모델 응답에서 유효한 판정 JSON을 뽑아내지 못했다."""


@dataclass(frozen=True)
class AxisScores:
    groundedness: int
    relevance: int
    no_hallucination: int
    rejection_appropriate: int
    naturalness: int
    reasoning: str

    @property
    def total(self) -> int:
        return (
            self.groundedness
            + self.relevance
            + self.no_hallucination
            + self.rejection_appropriate
            + self.naturalness
        )


@dataclass(frozen=True)
class JudgeVerdict:
    scores: AxisScores | None
    success: bool
    error: str | None = None


def _parse_verdict_json(text: str) -> AxisScores:
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if match is None:
        raise JudgeParseError(f"JSON 객체를 찾을 수 없습니다: {text!r}")
    try:
        payload = json.loads(match.group(0))
    except json.JSONDecodeError as exc:
        raise JudgeParseError(f"JSON 파싱 실패: {exc}") from exc
    if not isinstance(payload, dict):
        raise JudgeParseError("응답이 JSON 객체가 아닙니다.")

    scores: dict[str, int] = {}
    for axis in AXIS_NAMES:
        value = payload.get(axis)
        if not isinstance(value, int) or isinstance(value, bool) or not (0 <= value <= 2):
            raise JudgeParseError(f"{axis} 값이 0~2 정수가 아닙니다: {value!r}")
        scores[axis] = value

    reasoning = payload.get("reasoning")
    if not isinstance(reasoning, str):
        reasoning = ""
    return AxisScores(**scores, reasoning=reasoning)


class LLMJudge:
    """`GmsChatClient`로 채점 모델을 호출해 답변 하나를 5축으로 채점한다."""

    def __init__(self, *, client: GmsChatClient, judge_model: ModelSpec) -> None:
        self._client = client
        self._judge_model = judge_model

    def score(
        self,
        *,
        query: str,
        gold_answer_status: str,
        gold_answer: str,
        candidate_answer: str,
    ) -> JudgeVerdict:
        if not candidate_answer.strip():
            return JudgeVerdict(
                scores=None, success=False, error="채점 대상 답변이 비어 있습니다."
            )

        user_prompt = (
            f"질문: {query}\n"
            f"Gold 판정: {gold_answer_status}\n"
            f"Gold 정답: {gold_answer}\n\n"
            f"채점 대상 답변:\n{candidate_answer}"
        )
        result = self._client.complete(
            system_prompt=JUDGE_SYSTEM_PROMPT,
            user_prompt=user_prompt,
            model=self._judge_model,
            temperature=0.0,
        )
        if not result.success:
            return JudgeVerdict(scores=None, success=False, error=result.error)

        try:
            scores = _parse_verdict_json(result.text)
        except JudgeParseError as exc:
            return JudgeVerdict(scores=None, success=False, error=str(exc))
        return JudgeVerdict(scores=scores, success=True)


def score_report(report: dict[str, Any], judge: LLMJudge) -> dict[str, Any]:
    """`generation.py` 비교 report(JSON)를 순회해 case×model별 채점 결과를 만든다.

    생성 자체가 실패한 case×model(``model_answers[*].success=False``)은 채점
    호출을 생략하고 그대로 실패로 기록한다 — 빈 답변을 채점하는 건 의미가 없다.
    """
    results: dict[str, Any] = {}
    for question in report["questions"]:
        case_id = question["case_id"]
        case_scores: dict[str, Any] = {}
        for model_id, answer in question["model_answers"].items():
            if not answer.get("success"):
                case_scores[model_id] = {
                    "success": False,
                    "error": answer.get("error") or "생성 실패로 채점 생략",
                }
                continue

            verdict = judge.score(
                query=question["query"],
                gold_answer_status=question["gold_answer_status"],
                gold_answer=question["gold_answer"],
                candidate_answer=answer["answer"],
            )
            if verdict.success and verdict.scores is not None:
                case_scores[model_id] = {
                    "success": True,
                    **{axis: getattr(verdict.scores, axis) for axis in AXIS_NAMES},
                    "total": verdict.scores.total,
                    "reasoning": verdict.scores.reasoning,
                }
            else:
                case_scores[model_id] = {"success": False, "error": verdict.error}
        results[case_id] = case_scores
    return results
