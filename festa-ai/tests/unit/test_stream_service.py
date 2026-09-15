"""Verify the C-07 SSE envelope sequence and terminal exclusivity (S15P21A604-140/141)."""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timedelta, timezone

import pytest

from app.clients.spring_agent_config import (
    AgentConfigDenied,
    SpringAgentConfigUnavailable,
)
from app.clients.spring_chunk_search import RetrievedChunk
from app.models.conversation import Conversation
from app.providers.llm import LLMRequest, LLMToken
from app.providers.managed_llm import ManagedLLMError
from app.services.context_service import ContextBuildResult
from app.services.rag_service import NoReadyContextResult
from app.services.stream_service import (
    BoothLeaseExpired,
    ConversationNotFound,
    ConversationOwnershipMismatch,
    ConversationStreamService,
)
from tests.fakes.llm import FakeLLMProvider


class _DelayedLLMProvider:
    """지정한 간격만큼 기다렸다 token을 내보내는 가짜 LLM Provider (S15P21A604-141)."""

    model_id = "fake-llm-delayed"

    def __init__(self, delays_and_tokens: list[tuple[float, str]]) -> None:
        self._plan = delays_and_tokens

    async def stream(self, request: LLMRequest):
        for delay, text in self._plan:
            await asyncio.sleep(delay)
            yield LLMToken(text=text)

NOW = datetime(2026, 9, 6, tzinfo=timezone.utc)


def _conversation(*, user_id: int = 1, lease_ends_at: datetime | None = None) -> Conversation:
    return Conversation.create(
        conversation_id="conv_1",
        user_id=user_id,
        booth_id=10,
        agent_id=20,
        lease_ends_at=lease_ends_at or (NOW + timedelta(hours=1)),
        now=NOW,
        ttl_seconds=1800,
    )


def _chunk(document_id: int, chunk_id: int, *, original_filename: str = "문서.pdf") -> RetrievedChunk:
    return RetrievedChunk(
        chunk_id=chunk_id,
        document_id=document_id,
        content="본문",
        page_number=1,
        section=None,
        original_filename=original_filename,
        distance=0.1,
    )


def _context_result(chunks: tuple[RetrievedChunk, ...] = ()) -> ContextBuildResult:
    return ContextBuildResult(
        request=LLMRequest(messages=()),
        included_chunks=chunks,
        token_count=10,
        max_output_tokens=400,
        included_turn_count=0,
        omitted_chunk_count=0,
        omitted_turn_count=0,
        omitted_summary=False,
    )


class _ConversationRepository:
    def __init__(self, conversation: Conversation | None) -> None:
        self._conversation = conversation
        self.saved: list[Conversation] = []

    async def get(self, conversation_id: str) -> Conversation | None:
        return self._conversation

    async def save(self, conversation: Conversation) -> None:
        self.saved.append(conversation)

    async def commit_turn(self, conversation: Conversation) -> bool:
        if self._conversation is None:
            return False
        self.saved.append(conversation)
        return True


class _RagContextService:
    def __init__(self, result: ContextBuildResult | None = None, error: Exception | None = None):
        self._result = result
        self._error = error

    async def build(self, *, conversation, question):
        if self._error is not None:
            raise self._error
        return self._result


def _service(
    *,
    conversation: Conversation | None,
    rag: _RagContextService,
    llm,
    clock=lambda: NOW,
    ttft_timeout_seconds: float = 15.0,
    total_timeout_seconds: float = 60.0,
) -> tuple[ConversationStreamService, _ConversationRepository]:
    repository = _ConversationRepository(conversation)
    service = ConversationStreamService(
        repository=repository,
        rag_context_service=rag,
        llm_provider=llm,
        ttl_seconds=1800,
        clock=clock,
        ttft_timeout_seconds=ttft_timeout_seconds,
        total_timeout_seconds=total_timeout_seconds,
    )
    return service, repository


def _events(raw_events: list[str]) -> list[dict]:
    parsed = []
    for raw in raw_events:
        data_line = next(line for line in raw.splitlines() if line.startswith("data: "))
        parsed.append(json.loads(data_line[len("data: ") :]))
    return parsed


def _assert_c07_contract(raw_events: list[str], *, terminal: str) -> list[dict]:
    """Pin the wire-level C-07 rules shared with the frontend SSE parser."""
    events = _events(raw_events)

    for sequence, (raw, event) in enumerate(zip(raw_events, events, strict=True)):
        event_line = next(line for line in raw.splitlines() if line.startswith("event: "))
        assert event_line.removeprefix("event: ") == event["type"]
        assert {"type", "requestId", "conversationId", "messageId", "sequence"} <= event.keys()
        assert event["sequence"] == sequence

    assert events[-1]["type"] == terminal
    assert [event["type"] for event in events if event["type"] in {"done", "error"}] == [terminal]
    assert len({event["requestId"] for event in events}) == 1
    assert len({event["conversationId"] for event in events}) == 1
    assert len({event["messageId"] for event in events}) == 1
    return events


@pytest.mark.asyncio
async def test_authorize_raises_not_found_when_conversation_missing() -> None:
    service, _ = _service(conversation=None, rag=_RagContextService(), llm=FakeLLMProvider())

    with pytest.raises(ConversationNotFound):
        await service.authorize(conversation_id="conv_1", user_id=1)


@pytest.mark.asyncio
async def test_authorize_raises_ownership_mismatch_for_other_user() -> None:
    service, _ = _service(
        conversation=_conversation(user_id=1), rag=_RagContextService(), llm=FakeLLMProvider()
    )

    with pytest.raises(ConversationOwnershipMismatch):
        await service.authorize(conversation_id="conv_1", user_id=999)


@pytest.mark.asyncio
async def test_authorize_raises_lease_expired_when_past_lease_ends_at() -> None:
    expired = _conversation(lease_ends_at=NOW - timedelta(seconds=1))
    service, _ = _service(conversation=expired, rag=_RagContextService(), llm=FakeLLMProvider())

    with pytest.raises(BoothLeaseExpired):
        await service.authorize(conversation_id="conv_1", user_id=1)


@pytest.mark.asyncio
async def test_successful_stream_emits_start_token_source_done_in_order() -> None:
    chunks = (
        _chunk(2001, 5001, original_filename="문서1.pdf"),
        _chunk(2002, 5002, original_filename="문서2.pdf"),
    )
    rag = _RagContextService(result=_context_result(chunks))
    llm = FakeLLMProvider(tokens=("안녕", "하세요"))
    service, repository = _service(
        conversation=_conversation(),
        rag=rag,
        llm=llm,
    )

    raw = [event async for event in service.stream(conversation=_conversation(), question="질문")]
    events = _assert_c07_contract(raw, terminal="done")

    types = [event["type"] for event in events]
    assert types == ["start", "token", "token", "source", "source", "done"]
    assert [event["sequence"] for event in events] == [0, 1, 2, 3, 4, 5]
    assert all(event["conversationId"] == "conv_1" for event in events)
    assert len({event["requestId"] for event in events}) == 1
    assert len({event["messageId"] for event in events}) == 1

    token_events = [event for event in events if event["type"] == "token"]
    assert [event["delta"] for event in token_events] == ["안녕", "하세요"]

    source_events = [event for event in events if event["type"] == "source"]
    assert {event["documentId"] for event in source_events} == {2001, 2002}
    assert {event["title"] for event in source_events} == {"문서1.pdf", "문서2.pdf"}

    done_event = events[-1]
    assert done_event["handoffRecommended"] is False

    assert len(repository.saved) == 1
    saved_turn = repository.saved[0].turns[-1]
    assert saved_turn.question == "질문"
    assert saved_turn.answer == "안녕하세요"
    assert {source.document_id for source in saved_turn.sources} == {2001, 2002}


@pytest.mark.asyncio
async def test_duplicate_document_chunk_pair_emits_one_source_event() -> None:
    chunks = (_chunk(2001, 5001), _chunk(2001, 5001))
    rag = _RagContextService(result=_context_result(chunks))
    service, _ = _service(
        conversation=_conversation(), rag=rag, llm=FakeLLMProvider(tokens=("답",))
    )

    events = _assert_c07_contract(
        [event async for event in service.stream(conversation=_conversation(), question="질문")],
        terminal="done",
    )

    assert len([event for event in events if event["type"] == "source"]) == 1


@pytest.mark.asyncio
async def test_context_build_failure_emits_single_error_event_and_no_commit(caplog) -> None:
    raw_question = "개인 원문 질문입니다"
    raw_error = "provider 원문 오류입니다"
    rag = _RagContextService(error=RuntimeError(raw_error))
    service, repository = _service(
        conversation=_conversation(), rag=rag, llm=FakeLLMProvider()
    )

    with caplog.at_level(logging.ERROR, logger="app.services.stream_service"):
        events = _assert_c07_contract(
            [
                event
                async for event in service.stream(
                    conversation=_conversation(), question=raw_question
                )
            ],
            terminal="error",
        )

    assert [event["type"] for event in events] == ["start", "error"]
    assert events[-1]["code"] == "CONTEXT_BUILD_FAILED"
    assert repository.saved == []
    record = caplog.records[-1]
    assert record.getMessage() == "conversation_stream_failed"
    assert record.conversation_id == "conv_1"
    assert record.error_code == "CONTEXT_BUILD_FAILED"
    assert raw_question not in caplog.text
    assert raw_error not in caplog.text


@pytest.mark.asyncio
@pytest.mark.parametrize("code", ["AGENT_NOT_IN_BOOTH", "AGENT_INACTIVE"])
async def test_agent_config_denial_emits_non_retryable_sanitized_error(code: str) -> None:
    rag = _RagContextService(error=AgentConfigDenied(code))
    service, repository = _service(
        conversation=_conversation(), rag=rag, llm=FakeLLMProvider()
    )

    events = _events(
        [event async for event in service.stream(conversation=_conversation(), question="질문")]
    )

    assert [event["type"] for event in events] == ["start", "error"]
    assert events[-1]["code"] == code
    assert events[-1]["retryable"] is False
    assert repository.saved == []


@pytest.mark.asyncio
async def test_agent_config_failure_emits_retryable_sanitized_error() -> None:
    rag = _RagContextService(
        error=SpringAgentConfigUnavailable("upstream-secret-prompt")
    )
    service, repository = _service(
        conversation=_conversation(), rag=rag, llm=FakeLLMProvider()
    )

    events = _events(
        [event async for event in service.stream(conversation=_conversation(), question="질문")]
    )

    assert [event["type"] for event in events] == ["start", "error"]
    assert events[-1]["code"] == "AGENT_CONFIG_UNAVAILABLE"
    assert events[-1]["retryable"] is True
    assert "upstream-secret-prompt" not in events[-1]["message"]
    assert repository.saved == []


@pytest.mark.asyncio
async def test_llm_failure_mid_stream_emits_single_error_event_and_no_commit() -> None:
    rag = _RagContextService(result=_context_result())
    llm = FakeLLMProvider(
        tokens=("일부",),
        raise_after=ManagedLLMError("LLM_TIMEOUT", retryable=True),
    )
    service, repository = _service(conversation=_conversation(), rag=rag, llm=llm)

    events = _events(
        [event async for event in service.stream(conversation=_conversation(), question="질문")]
    )

    types = [event["type"] for event in events]
    assert types == ["start", "token", "error"]
    assert events[-1]["code"] == "LLM_TIMEOUT"
    assert events[-1]["retryable"] is True
    assert repository.saved == []


@pytest.mark.asyncio
async def test_retry_after_failure_issues_new_request_id_and_commits_only_retry() -> None:
    """FR-029: 실패 후 재시도는 conversationId를 유지하고 새 requestId를 발급하며,
    done에 도달하지 못한 첫 시도는 대화 이력에 저장되지 않는다."""
    conversation = _conversation()
    failing_llm = FakeLLMProvider(
        tokens=("일부",), raise_after=ManagedLLMError("LLM_TIMEOUT", retryable=True)
    )
    service, repository = _service(
        conversation=conversation,
        rag=_RagContextService(result=_context_result()),
        llm=failing_llm,
    )

    failed_events = _assert_c07_contract(
        [event async for event in service.stream(conversation=conversation, question="질문")],
        terminal="error",
    )
    assert [event["type"] for event in failed_events] == ["start", "token", "error"]
    assert repository.saved == []

    succeeding_llm = FakeLLMProvider(tokens=("안녕",))
    service._llm_provider = succeeding_llm
    retry_events = _assert_c07_contract(
        [event async for event in service.stream(conversation=conversation, question="질문")],
        terminal="done",
    )

    assert [event["type"] for event in retry_events] == ["start", "token", "done"]
    failed_request_id = failed_events[0]["requestId"]
    retry_request_id = retry_events[0]["requestId"]
    failed_message_id = failed_events[0]["messageId"]
    retry_message_id = retry_events[0]["messageId"]
    assert retry_request_id != failed_request_id
    assert retry_message_id != failed_message_id
    assert {event["conversationId"] for event in failed_events + retry_events} == {
        conversation.conversation_id
    }
    assert len(repository.saved) == 1
    assert repository.saved[0].turns[-1].request_id == retry_request_id


@pytest.mark.asyncio
async def test_ttft_timeout_before_first_token_emits_first_token_phase() -> None:
    """FR-007: 첫 토큰이 TTFT 예산 안에 오지 않으면 FIRST_TOKEN phase로 종료한다."""
    rag = _RagContextService(result=_context_result())
    llm = _DelayedLLMProvider([(0.05, "늦은토큰")])
    service, repository = _service(
        conversation=_conversation(),
        rag=rag,
        llm=llm,
        ttft_timeout_seconds=0.01,
        total_timeout_seconds=1.0,
    )

    events = _assert_c07_contract(
        [event async for event in service.stream(conversation=_conversation(), question="질문")],
        terminal="error",
    )

    assert [event["type"] for event in events] == ["start", "error"]
    assert events[-1]["code"] == "LLM_TIMEOUT"
    assert events[-1]["timeoutPhase"] == "FIRST_TOKEN"
    assert events[-1]["retryable"] is True
    assert repository.saved == []


@pytest.mark.asyncio
async def test_total_timeout_after_first_token_emits_total_response_phase() -> None:
    """FR-007: 첫 토큰 이후라도 전체 60초를 넘기면 TOTAL_RESPONSE phase로 종료한다."""
    rag = _RagContextService(result=_context_result())
    llm = _DelayedLLMProvider([(0.0, "빠른"), (0.05, "느린")])
    service, repository = _service(
        conversation=_conversation(),
        rag=rag,
        llm=llm,
        ttft_timeout_seconds=1.0,
        total_timeout_seconds=0.02,
    )

    events = _assert_c07_contract(
        [event async for event in service.stream(conversation=_conversation(), question="질문")],
        terminal="error",
    )

    assert [event["type"] for event in events] == ["start", "token", "error"]
    assert events[-1]["code"] == "LLM_TIMEOUT"
    assert events[-1]["timeoutPhase"] == "TOTAL_RESPONSE"
    assert events[-1]["retryable"] is True
    assert repository.saved == []


@pytest.mark.asyncio
async def test_no_ready_context_skips_llm_and_streams_fixed_message() -> None:
    """SC-008: READY 0건이면 LLM 호출 0건이고 자료 미준비 안내를 낸다."""
    rag = _RagContextService(result=NoReadyContextResult())
    llm = FakeLLMProvider(tokens=("절대", "호출되면", "안됨"))
    service, repository = _service(conversation=_conversation(), rag=rag, llm=llm)

    events = _events(
        [event async for event in service.stream(conversation=_conversation(), question="질문")]
    )

    assert [event["type"] for event in events] == ["start", "token", "done"]
    assert events[1]["delta"] == NoReadyContextResult().message
    assert llm.calls == []

    assert len(repository.saved) == 1
    saved_turn = repository.saved[0].turns[-1]
    assert saved_turn.answer == NoReadyContextResult().message
    assert saved_turn.sources == ()


@pytest.mark.asyncio
async def test_empty_answer_completes_without_committing_a_turn() -> None:
    rag = _RagContextService(result=_context_result())
    service, repository = _service(
        conversation=_conversation(), rag=rag, llm=FakeLLMProvider(tokens=())
    )

    events = _events(
        [event async for event in service.stream(conversation=_conversation(), question="질문")]
    )

    assert [event["type"] for event in events] == ["start", "done"]
    assert repository.saved == []
