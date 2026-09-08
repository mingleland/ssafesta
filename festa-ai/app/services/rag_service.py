"""Build one LLM-ready context for a Conversation question (spec 008 FR-008/FR-017).

`S15P21A604-140` slice of T019: scope-safe retrieval + prompt assembly.
`S15P21A604-145`: READY 문서가 0개라 검색 결과가 0건이면 LLM을 호출하지 않고
`NoReadyContextResult`를 돌려준다 (SC-008 — Spring 검색 호출 1건, LLM 호출 0건,
자료 미준비 안내).
"""

from __future__ import annotations

from dataclasses import dataclass

from app.clients.spring_chunk_search import ChunkScope
from app.models.conversation import Conversation
from app.providers.agent_config import AgentConfigProvider
from app.services.context_service import CompletedTurn, ContextBuildResult, PromptBuilder
from app.services.vector_search_service import VectorSearchService

NO_READY_CONTEXT_MESSAGE = "아직 검색 가능한 자료가 준비되지 않았습니다. 잠시 후 다시 시도해 주세요."


@dataclass(frozen=True, slots=True)
class NoReadyContextResult:
    """READY Chunk가 0건일 때의 고정 응답 — LLM을 호출하지 않는다 (SC-008)."""

    message: str = NO_READY_CONTEXT_MESSAGE


class RagContextService:
    def __init__(
        self,
        *,
        vector_search: VectorSearchService,
        agent_config_provider: AgentConfigProvider,
        prompt_builder: PromptBuilder,
        retrieval_top_k: int,
    ) -> None:
        if retrieval_top_k <= 0:
            raise ValueError("retrieval_top_k must be a positive integer")
        self._vector_search = vector_search
        self._agent_config_provider = agent_config_provider
        self._prompt_builder = prompt_builder
        self._retrieval_top_k = retrieval_top_k

    async def build(
        self, *, conversation: Conversation, question: str
    ) -> ContextBuildResult | NoReadyContextResult:
        # Agent membership/activity must fail closed before Embedding and search.
        # The Spring contract requires exactly one uncached lookup per question.
        agent = await self._agent_config_provider.get(
            booth_id=conversation.scope.booth_id, agent_id=conversation.scope.agent_id
        )
        scope = ChunkScope(
            booth_id=conversation.scope.booth_id, agent_id=conversation.scope.agent_id
        )
        chunks = await self._vector_search.search(
            question=question, scope=scope, top_k=self._retrieval_top_k
        )
        if not chunks:
            return NoReadyContextResult()
        turns = tuple(
            CompletedTurn(question=turn.question, answer=turn.answer)
            for turn in conversation.turns
        )
        return self._prompt_builder.build(
            agent=agent, question=question, chunks=chunks, turns=turns
        )
