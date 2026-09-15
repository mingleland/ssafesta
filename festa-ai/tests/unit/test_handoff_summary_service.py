"""S15P21A604-139 — HandoffSummaryService 단위 테스트."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from datetime import datetime, timezone

import pytest

from app.models.conversation import Conversation, ConversationTurn
from app.providers.llm import LLMRequest, LLMToken
from app.providers.managed_llm import ManagedLLMError
from app.services.handoff_summary_service import (
    ConversationNotFound,
    HandoffSummary,
    HandoffSummaryGenerationFailed,
    HandoffSummaryService,
)

from tests.fakes.llm import FakeLLMProvider

_NOW = datetime(2026, 9, 11, tzinfo=timezone.utc)
_VALID_JSON = (
    '{"summary": "방문자가 참여 방법을 물었습니다.", '
    '"topics": ["참여 방법"], "lastUserIntent": "담당자와 연결을 원함"}'
)


class _FakeConversationRepository:
    def __init__(self, conversation: Conversation | None) -> None:
        self._conversation = conversation
        self.calls: list[str] = []

    async def get(self, conversation_id: str) -> Conversation | None:
        self.calls.append(conversation_id)
        return self._conversation


class _SlowLLMProvider:
    """timeout_seconds보다 오래 걸리는 LLM 호출을 흉내낸다."""

    model_id = "slow-fake-llm-v1"

    def __init__(self, *, delay_seconds: float) -> None:
        self._delay_seconds = delay_seconds
        self.calls: list[LLMRequest] = []

    async def stream(self, request: LLMRequest) -> AsyncIterator[LLMToken]:
        self.calls.append(request)
        await asyncio.sleep(self._delay_seconds)
        yield LLMToken(text="never reached")


def _conversation_without_turns() -> Conversation:
    return Conversation.create(
        conversation_id="conv_empty",
        user_id=1,
        booth_id=7,
        agent_id=3,
        lease_ends_at=_NOW,
        now=_NOW,
        ttl_seconds=1800,
    )


def _conversation_with_turns(count: int = 1) -> Conversation:
    conversation = _conversation_without_turns()
    for index in range(count):
        turn = ConversationTurn(
            request_id=f"req_{index}",
            user_message_id=f"umsg_{index}",
            assistant_message_id=f"amsg_{index}",
            question=f"질문 {index}",
            answer=f"답변 {index}",
            sources=(),
            created_at=_NOW,
        )
        conversation = conversation.record_turn(turn, now=_NOW, ttl_seconds=1800)
    return conversation


@pytest.mark.asyncio
async def test_raises_conversation_not_found() -> None:
    repository = _FakeConversationRepository(None)
    service = HandoffSummaryService(
        repository=repository, llm_provider=FakeLLMProvider()
    )

    with pytest.raises(ConversationNotFound):
        await service.summarize("conv_missing")


@pytest.mark.asyncio
async def test_empty_conversation_returns_canned_summary_without_calling_llm() -> None:
    repository = _FakeConversationRepository(_conversation_without_turns())
    llm_provider = FakeLLMProvider(tokens=(_VALID_JSON,))
    service = HandoffSummaryService(repository=repository, llm_provider=llm_provider)

    result = await service.summarize("conv_empty")

    assert result == HandoffSummary(summary=result.summary, topics=(), last_user_intent="")
    assert llm_provider.calls == []


@pytest.mark.asyncio
async def test_happy_path_parses_json_split_across_chunks() -> None:
    repository = _FakeConversationRepository(_conversation_with_turns())
    # 실제 스트리밍처럼 여러 조각으로 쪼개 보내도 이어붙여 파싱돼야 한다.
    chunks = [_VALID_JSON[i : i + 5] for i in range(0, len(_VALID_JSON), 5)]
    llm_provider = FakeLLMProvider(tokens=chunks)
    service = HandoffSummaryService(repository=repository, llm_provider=llm_provider)

    result = await service.summarize("conv_abc")

    assert result == HandoffSummary(
        summary="방문자가 참여 방법을 물었습니다.",
        topics=("참여 방법",),
        last_user_intent="담당자와 연결을 원함",
    )
    assert len(llm_provider.calls) == 1


@pytest.mark.asyncio
async def test_malformed_json_raises_parse_failed() -> None:
    repository = _FakeConversationRepository(_conversation_with_turns())
    llm_provider = FakeLLMProvider(tokens=("이건 JSON이 아닙니다",))
    service = HandoffSummaryService(repository=repository, llm_provider=llm_provider)

    with pytest.raises(HandoffSummaryGenerationFailed) as exc_info:
        await service.summarize("conv_abc")
    assert exc_info.value.code == "HANDOFF_SUMMARY_PARSE_FAILED"


@pytest.mark.asyncio
async def test_json_missing_required_key_raises_parse_failed() -> None:
    repository = _FakeConversationRepository(_conversation_with_turns())
    llm_provider = FakeLLMProvider(tokens=('{"summary": "요약만 있음"}',))
    service = HandoffSummaryService(repository=repository, llm_provider=llm_provider)

    with pytest.raises(HandoffSummaryGenerationFailed) as exc_info:
        await service.summarize("conv_abc")
    assert exc_info.value.code == "HANDOFF_SUMMARY_PARSE_FAILED"


@pytest.mark.asyncio
async def test_json_with_wrong_field_type_raises_parse_failed() -> None:
    repository = _FakeConversationRepository(_conversation_with_turns())
    llm_provider = FakeLLMProvider(
        tokens=('{"summary": "요약", "topics": "문자열이면 안됨", "lastUserIntent": "의도"}',)
    )
    service = HandoffSummaryService(repository=repository, llm_provider=llm_provider)

    with pytest.raises(HandoffSummaryGenerationFailed) as exc_info:
        await service.summarize("conv_abc")
    assert exc_info.value.code == "HANDOFF_SUMMARY_PARSE_FAILED"


@pytest.mark.asyncio
async def test_llm_provider_error_is_propagated_with_its_code() -> None:
    repository = _FakeConversationRepository(_conversation_with_turns())
    llm_provider = FakeLLMProvider(
        raise_after=ManagedLLMError("LLM_PROVIDER_ERROR", retryable=True)
    )
    service = HandoffSummaryService(repository=repository, llm_provider=llm_provider)

    with pytest.raises(HandoffSummaryGenerationFailed) as exc_info:
        await service.summarize("conv_abc")
    assert exc_info.value.code == "LLM_PROVIDER_ERROR"


@pytest.mark.asyncio
async def test_llm_timeout_raises_llm_timeout_code() -> None:
    repository = _FakeConversationRepository(_conversation_with_turns())
    llm_provider = _SlowLLMProvider(delay_seconds=0.2)
    service = HandoffSummaryService(
        repository=repository, llm_provider=llm_provider, timeout_seconds=0.01
    )

    with pytest.raises(HandoffSummaryGenerationFailed) as exc_info:
        await service.summarize("conv_abc")
    assert exc_info.value.code == "LLM_TIMEOUT"
