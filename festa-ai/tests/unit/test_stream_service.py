"""Verify the C-07 SSE envelope sequence and terminal exclusivity (S15P21A604-140)."""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest

from app.models.conversation import Conversation
from app.providers.llm import LLMRequest
from app.providers.managed_llm import ManagedLLMError
from app.repositories.chunk_repository import RetrievedChunk
from app.services.context_service import ContextBuildResult
from app.services.stream_service import (
    BoothLeaseExpired,
    ConversationNotFound,
    ConversationOwnershipMismatch,
    ConversationStreamService,
)
from tests.fakes.llm import FakeLLMProvider

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


def _chunk(document_id: int, chunk_id: int) -> RetrievedChunk:
    return RetrievedChunk(
        chunk_id=chunk_id,
        document_id=document_id,
        booth_id=10,
        agent_id=20,
        chunk_no=0,
        content="본문",
        embedding_model_id="text-embedding-3-large",
        page_number=1,
        section=None,
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


class _RagContextService:
    def __init__(self, result: ContextBuildResult | None = None, error: Exception | None = None):
        self._result = result
        self._error = error

    async def build(self, *, conversation, question):
        if self._error is not None:
            raise self._error
        return self._result


class _DocumentJobRepository:
    def __init__(self, titles: dict[int, str] | None = None) -> None:
        self._titles = titles or {}

    async def find_titles(self, document_ids):
        return {doc_id: self._titles[doc_id] for doc_id in document_ids if doc_id in self._titles}


def _service(
    *,
    conversation: Conversation | None,
    rag: _RagContextService,
    llm,
    titles: dict[int, str] | None = None,
    clock=lambda: NOW,
) -> tuple[ConversationStreamService, _ConversationRepository]:
    repository = _ConversationRepository(conversation)
    service = ConversationStreamService(
        repository=repository,
        rag_context_service=rag,
        llm_provider=llm,
        document_job_repository=_DocumentJobRepository(titles),
        ttl_seconds=1800,
        clock=clock,
    )
    return service, repository


def _events(raw_events: list[str]) -> list[dict]:
    parsed = []
    for raw in raw_events:
        data_line = next(line for line in raw.splitlines() if line.startswith("data: "))
        parsed.append(json.loads(data_line[len("data: ") :]))
    return parsed


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
    chunks = (_chunk(2001, 5001), _chunk(2002, 5002))
    rag = _RagContextService(result=_context_result(chunks))
    llm = FakeLLMProvider(tokens=("안녕", "하세요"))
    service, repository = _service(
        conversation=_conversation(),
        rag=rag,
        llm=llm,
        titles={2001: "문서1.pdf", 2002: "문서2.pdf"},
    )

    raw = [event async for event in service.stream(conversation=_conversation(), question="질문")]
    events = _events(raw)

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

    events = _events(
        [event async for event in service.stream(conversation=_conversation(), question="질문")]
    )

    assert len([event for event in events if event["type"] == "source"]) == 1


@pytest.mark.asyncio
async def test_context_build_failure_emits_single_error_event_and_no_commit() -> None:
    rag = _RagContextService(error=RuntimeError("boom"))
    service, repository = _service(
        conversation=_conversation(), rag=rag, llm=FakeLLMProvider()
    )

    events = _events(
        [event async for event in service.stream(conversation=_conversation(), question="질문")]
    )

    assert [event["type"] for event in events] == ["start", "error"]
    assert events[-1]["code"] == "CONTEXT_BUILD_FAILED"
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
