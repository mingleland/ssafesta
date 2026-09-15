"""S15P21A604-139 — Handoff Summary 생성 (spec 011 FR-008/FR-012, D11).

`LLMProvider` 프로토콜에는 스트리밍(`stream()`)만 있고 구조화(JSON) 단발 호출은
없다 — GMS 백엔드가 `response_format=json_object`를 지원한다는 근거가 없어서
프로토콜을 확장하지 않고, 기존 `.stream()` token을 이어붙여 하나의 JSON 문자열로
파싱하는 쪽을 택했다(계획 결정 2). 파싱 실패·LLM timeout·Provider 오류는 전부
`HandoffSummaryGenerationFailed`로 정규화해 라우트가 503으로 매핑한다.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass

from app.providers.llm import LLMProvider, LLMRequest
from app.providers.managed_llm import ManagedLLMError
from app.repositories.conversation_repository import ConversationRepository
from app.services.handoff_summary_prompt import (
    HANDOFF_SUMMARY_MAX_OUTPUT_TOKENS,
    build_handoff_summary_request,
)
from app.services.stream_service import ConversationNotFound

__all__ = [
    "ConversationNotFound",
    "HandoffSummary",
    "HandoffSummaryGenerationFailed",
    "HandoffSummaryService",
]


class HandoffSummaryGenerationFailed(Exception):
    """LLM timeout·provider 오류·JSON 파싱 실패를 하나의 도메인 예외로 정규화한다."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True, slots=True)
class HandoffSummary:
    summary: str
    topics: tuple[str, ...]
    last_user_intent: str


_EMPTY_CONVERSATION_SUMMARY = HandoffSummary(
    summary="아직 방문자와 나눈 대화가 없습니다.",
    topics=(),
    last_user_intent="",
)


def _extract_json(raw_text: str) -> object:
    """LLM이 코드펜스·잡담을 덧붙였을 때를 대비해 한 번만 관대하게 재시도한다."""
    stripped = raw_text.strip()
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        pass

    start = stripped.find("{")
    end = stripped.rfind("}")
    if start == -1 or end == -1 or end < start:
        raise HandoffSummaryGenerationFailed("HANDOFF_SUMMARY_PARSE_FAILED")
    try:
        return json.loads(stripped[start : end + 1])
    except json.JSONDecodeError as exc:
        raise HandoffSummaryGenerationFailed("HANDOFF_SUMMARY_PARSE_FAILED") from exc


class HandoffSummaryService:
    def __init__(
        self,
        *,
        repository: ConversationRepository,
        llm_provider: LLMProvider,
        timeout_seconds: float = 20.0,
        max_output_tokens: int = HANDOFF_SUMMARY_MAX_OUTPUT_TOKENS,
    ) -> None:
        self._repository = repository
        self._llm_provider = llm_provider
        self._timeout_seconds = timeout_seconds
        self._max_output_tokens = max_output_tokens

    async def summarize(self, conversation_id: str) -> HandoffSummary:
        conversation = await self._repository.get(conversation_id)
        if conversation is None:
            raise ConversationNotFound(conversation_id)

        if not conversation.turns:
            # 아직 확정된 turn이 없으면 LLM을 부르지 않는다 — 근거 없이 지어낼 필요가 없다.
            return _EMPTY_CONVERSATION_SUMMARY

        request = build_handoff_summary_request(
            conversation.turns, max_output_tokens=self._max_output_tokens
        )
        raw_text = await self._collect_text(request)
        return self._parse(raw_text)

    async def _collect_text(self, request: LLMRequest) -> str:
        async def _consume() -> str:
            parts: list[str] = []
            async for token in self._llm_provider.stream(request):
                parts.append(token.text)
            return "".join(parts)

        try:
            return await asyncio.wait_for(_consume(), timeout=self._timeout_seconds)
        except TimeoutError as exc:
            raise HandoffSummaryGenerationFailed("LLM_TIMEOUT") from exc
        except ManagedLLMError as exc:
            raise HandoffSummaryGenerationFailed(exc.code) from exc

    @staticmethod
    def _parse(raw_text: str) -> HandoffSummary:
        data = _extract_json(raw_text)
        if not isinstance(data, dict):
            raise HandoffSummaryGenerationFailed("HANDOFF_SUMMARY_PARSE_FAILED")

        summary = data.get("summary")
        topics = data.get("topics")
        last_user_intent = data.get("lastUserIntent")
        if (
            not isinstance(summary, str)
            or not summary.strip()
            or not isinstance(topics, list)
            or not all(isinstance(topic, str) for topic in topics)
            or not isinstance(last_user_intent, str)
        ):
            raise HandoffSummaryGenerationFailed("HANDOFF_SUMMARY_PARSE_FAILED")

        return HandoffSummary(
            summary=summary,
            topics=tuple(topics),
            last_user_intent=last_user_intent,
        )
