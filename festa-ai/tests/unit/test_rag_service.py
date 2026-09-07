"""Verify RagContextService wires retrieval + Agent config into PromptBuilder (S15P21A604-140)."""

from __future__ import annotations

import pytest

from app.clients.spring_chunk_search import ChunkScope, RetrievedChunk
from app.models.conversation import Conversation
from app.services.context_service import AgentPromptConfig, CompletedTurn
from app.services.rag_service import NoReadyContextResult, RagContextService


def _chunk(index: int) -> RetrievedChunk:
    return RetrievedChunk(
        chunk_id=1000 + index,
        document_id=2000 + index,
        content=f"content-{index}",
        page_number=index,
        section=None,
        original_filename=f"doc-{index}.pdf",
        distance=index / 10,
    )


class _VectorSearch:
    def __init__(self, chunks: tuple[RetrievedChunk, ...]) -> None:
        self._chunks = chunks
        self.calls: list[dict[str, object]] = []

    async def search(self, *, question, scope, top_k):
        self.calls.append({"question": question, "scope": scope, "top_k": top_k})
        return self._chunks


class _AgentConfigProvider:
    def __init__(self, config: AgentPromptConfig) -> None:
        self._config = config
        self.calls: list[dict[str, int]] = []

    async def get(self, *, booth_id, agent_id):
        self.calls.append({"booth_id": booth_id, "agent_id": agent_id})
        return self._config


class _PromptBuilder:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    def build(self, *, agent, question, chunks, turns):
        self.calls.append(
            {"agent": agent, "question": question, "chunks": chunks, "turns": turns}
        )
        return "BUILD_RESULT"


def _agent_config() -> AgentPromptConfig:
    return AgentPromptConfig(
        role="GUIDE",
        tone="FRIENDLY",
        response_length="MEDIUM",
        system_prompt="안내한다.",
    )


def _conversation(**turn_kwargs) -> Conversation:
    from datetime import datetime, timezone

    now = datetime(2026, 9, 6, tzinfo=timezone.utc)
    conversation = Conversation.create(
        conversation_id="conv_1",
        user_id=1,
        booth_id=10,
        agent_id=20,
        lease_ends_at=now,
        now=now,
        ttl_seconds=1800,
    )
    if turn_kwargs:
        from app.models.conversation import ConversationTurn

        turn = ConversationTurn(
            request_id="req",
            user_message_id="u",
            assistant_message_id="a",
            sources=(),
            created_at=now,
            **turn_kwargs,
        )
        conversation = conversation.record_turn(turn, now=now, ttl_seconds=1800)
    return conversation


@pytest.mark.asyncio
async def test_build_uses_conversation_scope_for_retrieval() -> None:
    chunks = (_chunk(1), _chunk(2))
    vector_search = _VectorSearch(chunks)
    prompt_builder = _PromptBuilder()
    service = RagContextService(
        vector_search=vector_search,
        agent_config_provider=_AgentConfigProvider(_agent_config()),
        prompt_builder=prompt_builder,
        retrieval_top_k=5,
    )

    result = await service.build(conversation=_conversation(), question="질문입니다")

    assert result == "BUILD_RESULT"
    assert vector_search.calls[0]["scope"] == ChunkScope(booth_id=10, agent_id=20)
    assert vector_search.calls[0]["top_k"] == 5
    assert prompt_builder.calls[0]["chunks"] == chunks
    assert prompt_builder.calls[0]["turns"] == ()


@pytest.mark.asyncio
async def test_build_maps_conversation_turns_to_completed_turns() -> None:
    prompt_builder = _PromptBuilder()
    service = RagContextService(
        vector_search=_VectorSearch((_chunk(1),)),
        agent_config_provider=_AgentConfigProvider(_agent_config()),
        prompt_builder=prompt_builder,
        retrieval_top_k=5,
    )

    conversation = _conversation(question="이전 질문", answer="이전 답변")
    await service.build(conversation=conversation, question="새 질문")

    assert prompt_builder.calls[0]["turns"] == (
        CompletedTurn(question="이전 질문", answer="이전 답변"),
    )


@pytest.mark.asyncio
async def test_build_returns_no_ready_context_when_search_finds_zero_chunks() -> None:
    """SC-008: READY 0건이면 LLM 호출 0건 — Agent 설정도 조회하지 않는다."""
    agent_config_provider = _AgentConfigProvider(_agent_config())
    prompt_builder = _PromptBuilder()
    service = RagContextService(
        vector_search=_VectorSearch(()),
        agent_config_provider=agent_config_provider,
        prompt_builder=prompt_builder,
        retrieval_top_k=5,
    )

    result = await service.build(conversation=_conversation(), question="질문입니다")

    assert isinstance(result, NoReadyContextResult)
    assert result.message
    assert agent_config_provider.calls == []
    assert prompt_builder.calls == []


def test_rejects_non_positive_retrieval_top_k() -> None:
    with pytest.raises(ValueError):
        RagContextService(
            vector_search=_VectorSearch(()),
            agent_config_provider=_AgentConfigProvider(_agent_config()),
            prompt_builder=_PromptBuilder(),
            retrieval_top_k=0,
        )
