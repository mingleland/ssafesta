"""S15P21A604-139 — Jira 명시 테스트: "10턴 대화 fixture -> 요약 품질 검토".

`MAX_STORED_TURNS = 6`(`app/models/conversation.py`)이라 Conversation은 실제로
최근 6턴만 원문으로 보관한다 — 10턴을 직접 구성해 리포지토리에 넣는 방식은
`record_turn`이 강제하는 이 캡을 우회하는 것이라 production 경로를 검증하지
못한다. 그래서 이 테스트는 "10턴이 실제로 오갔고(매번 `record_turn` 호출),
밀려난 4턴의 원문은 어디에도 남지 않으며, 응답은 보관된 마지막 6턴만 근거로
만들어진다"는 해석을 고른다 — 계획 문서에 남긴 대로, 리뷰어가 다른 해석을
원하면 이 파일이 그 지점이다.

결정론적 LLM(FakeLLMProvider)을 쓰므로 "품질"은 사람이 하는 실제 평가가
아니라, "정확히 보관된 6턴만 프롬프트에 실렸고 응답에 원문이 새지 않았다"는
구조적 사실로 대신 확인한다 — `test_prompt_injection_isolation.py`가 스스로
밝히는 것과 같은 한계다.
"""

from __future__ import annotations

import asyncio
import json
from datetime import datetime, timezone

import fakeredis
import httpx
import pytest

from app.api.errors import ApiError, api_error_handler
from app.models.conversation import Conversation, ConversationTurn
from app.providers.llm import HANDOFF_SUMMARY_MARKER
from app.repositories.conversation_repository import ConversationRepository
from app.services.handoff_summary_service import HandoffSummaryService
from fastapi import FastAPI
from tests.fakes.llm import FakeLLMProvider

from app.api.v1.conversations import get_handoff_summary_service, router

AUTH_HEADERS = {"Authorization": "Bearer spring-token"}
_NOW = datetime(2026, 9, 11, tzinfo=timezone.utc)
_VALID_JSON = (
    '{"summary": "방문자가 프로젝트 참여 방법과 일정을 문의했습니다.", '
    '"topics": ["참여 방법", "일정"], "lastUserIntent": "담당자와 직접 상담을 원함"}'
)

# 10턴 — 마지막 4턴(0~3)은 record_turn 캡으로 밀려나고 6~9(뒤 6개)만 남는다.
_TEN_TURNS = tuple(
    (f"질문{i} — 프로젝트 관련 문의입니다", f"답변{i} — 문서 기준 안내입니다")
    for i in range(10)
)
# PII 유사 문자열 하나를 밀려나지 않는 마지막 턴에 심어, 응답에 새지 않는지도 같이 본다.
_TEN_TURNS = _TEN_TURNS[:-1] + (
    (
        "제 연락처는 010-1234-5678 입니다, 상담원이 연락 줄 수 있나요",
        "네, 010-1234-5678 로 상담원이 연락드리겠습니다",
    ),
)


def _conversation_with_ten_turns_recorded() -> Conversation:
    conversation = Conversation.create(
        conversation_id="conv_ten_turns",
        user_id=1,
        booth_id=7,
        agent_id=3,
        lease_ends_at=_NOW,
        now=_NOW,
        ttl_seconds=1800,
    )
    for index, (question, answer) in enumerate(_TEN_TURNS):
        turn = ConversationTurn(
            request_id=f"req_{index}",
            user_message_id=f"umsg_{index}",
            assistant_message_id=f"amsg_{index}",
            question=question,
            answer=answer,
            sources=(),
            created_at=_NOW,
        )
        conversation = conversation.record_turn(turn, now=_NOW, ttl_seconds=1800)
    return conversation


def test_max_stored_turns_actually_caps_at_six() -> None:
    """전제 고정 — 10턴을 넣었지만 6턴만 남는다는 production 동작 자체를 먼저 증명한다."""
    conversation = _conversation_with_ten_turns_recorded()
    assert len(conversation.turns) == 6
    retained_questions = {turn.question for turn in conversation.turns}
    # 밀려난 0~3번 질문은 없고, 남은 4~9번 질문만 있어야 한다.
    for index in range(4):
        assert _TEN_TURNS[index][0] not in retained_questions
    for index in range(4, 10):
        assert _TEN_TURNS[index][0] in retained_questions


@pytest.mark.asyncio
async def test_handoff_summary_reflects_only_retained_six_turns_and_leaks_no_raw_text() -> None:
    redis = fakeredis.FakeAsyncRedis(decode_responses=True)
    repository = ConversationRepository(redis, ttl_seconds=1800)
    conversation = _conversation_with_ten_turns_recorded()
    await repository.save(conversation)

    llm_provider = FakeLLMProvider(tokens=(_VALID_JSON,))
    service = HandoffSummaryService(repository=repository, llm_provider=llm_provider)

    app = FastAPI()
    app.state.settings = type(
        "Settings", (), {"internal_spring_to_ai_tokens": ["spring-token"]}
    )()
    app.add_exception_handler(ApiError, api_error_handler)
    app.include_router(router, prefix="/ai/v1")
    app.dependency_overrides[get_handoff_summary_service] = lambda: service

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/ai/v1/conversations/conv_ten_turns/handoff-summary", headers=AUTH_HEADERS
        )

    assert response.status_code == 200
    body = response.json()
    assert set(body.keys()) == {"conversationId", "summary", "topics", "lastUserIntent"}

    response_text = json.dumps(body, ensure_ascii=False)
    for question, answer in _TEN_TURNS:
        assert question not in response_text
        assert answer not in response_text
    assert "010-1234-5678" not in response_text

    # 프롬프트에는 밀려난 4턴이 전혀 실리지 않고, 보관된 6턴만 실린다.
    assert len(llm_provider.calls) == 1
    prompt_text = "\n".join(m.content for m in llm_provider.calls[0].messages)
    turn_pair_count = sum(
        1 for m in llm_provider.calls[0].messages if HANDOFF_SUMMARY_MARKER in m.content
    ) // 2
    assert turn_pair_count == 6
    for index in range(4):
        assert _TEN_TURNS[index][0] not in prompt_text
        assert _TEN_TURNS[index][1] not in prompt_text
    for index in range(4, 10):
        assert _TEN_TURNS[index][0] in prompt_text
        assert _TEN_TURNS[index][1] in prompt_text
