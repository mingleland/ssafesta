"""Build one LLM-ready context for a Conversation question (spec 008 FR-008/FR-017).

`S15P21A604-140` slice of T019: scope-safe retrieval + prompt assembly only.
The READY-document-count-zero fixed response (FR edge case, no LLM call) is
`S15P21A604-145`'s scope and is not implemented here — with zero `READY`
chunks this still calls the LLM, which the platform/safety instructions
already steer toward "문서에서 확인할 수 없습니다" (FR-009).
"""

from __future__ import annotations

from app.models.conversation import Conversation
from app.providers.agent_config import AgentConfigProvider
from app.repositories.chunk_repository import ChunkScope
from app.services.context_service import CompletedTurn, ContextBuildResult, PromptBuilder
from app.services.vector_search_service import VectorSearchService


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

    async def build(self, *, conversation: Conversation, question: str) -> ContextBuildResult:
        scope = ChunkScope(
            booth_id=conversation.scope.booth_id, agent_id=conversation.scope.agent_id
        )
        chunks = await self._vector_search.search(
            question=question, scope=scope, top_k=self._retrieval_top_k
        )
        agent = await self._agent_config_provider.get(
            booth_id=conversation.scope.booth_id, agent_id=conversation.scope.agent_id
        )
        turns = tuple(
            CompletedTurn(question=turn.question, answer=turn.answer)
            for turn in conversation.turns
        )
        return self._prompt_builder.build(
            agent=agent, question=question, chunks=chunks, turns=turns
        )
