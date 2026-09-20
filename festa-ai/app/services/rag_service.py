"""Build one LLM-ready context for a Conversation question (spec 008 FR-008/FR-017).

`S15P21A604-140` slice of T019: scope-safe retrieval + prompt assembly.
`S15P21A604-145`: READY 문서가 0개라 검색 결과가 0건이면 LLM을 호출하지 않고
`NoReadyContextResult`를 돌려준다 (SC-008 — Spring 검색 호출 1건, LLM 호출 0건,
자료 미준비 안내).
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from enum import StrEnum

from app.clients.spring_chunk_search import ChunkScope
from app.models.conversation import Conversation
from app.providers.agent_config import AgentConfigProvider
from app.services.context_service import (
    CompletedTurn,
    ContextBuildResult,
    ProjectFacts,
    PromptBuilder,
)
from app.services.vector_search_service import VectorSearchService

NO_READY_CONTEXT_MESSAGE = "아직 검색 가능한 자료가 준비되지 않았습니다. 잠시 후 다시 시도해 주세요."


@dataclass(frozen=True, slots=True)
class NoReadyContextResult:
    """READY Chunk가 0건일 때의 고정 응답 — LLM을 호출하지 않는다 (SC-008)."""

    message: str = NO_READY_CONTEXT_MESSAGE


class QuickAnswerIntent(StrEnum):
    PROJECT_INTRODUCTION = "PROJECT_INTRODUCTION"
    TARGET_AUDIENCE = "TARGET_AUDIENCE"
    TECH_STACK = "TECH_STACK"


@dataclass(frozen=True, slots=True)
class QuickAnswerResult:
    """정확한 화이트리스트 질문에 저장된 정형값으로 답하는 단축 결과다."""

    intent: QuickAnswerIntent
    message: str


_QUICK_QUESTIONS: dict[QuickAnswerIntent, frozenset[str]] = {
    QuickAnswerIntent.PROJECT_INTRODUCTION: frozenset(
        {
            "프로젝트 소개",
            "프로젝트를 소개해줘",
            "프로젝트를 소개해주세요",
            "무슨 프로젝트야",
            "무슨 프로젝트인가요",
            "이 프로젝트는 뭐야",
            "이 프로젝트는 무엇인가요",
        }
    ),
    QuickAnswerIntent.TARGET_AUDIENCE: frozenset(
        {
            "대상 사용자",
            "대상 사용자는 누구야",
            "대상 사용자는 누구인가요",
            "어떤 사용자를 위한 프로젝트야",
            "어떤 사용자를 위한 프로젝트인가요",
            "타깃 사용자는 누구야",
            "타깃 사용자는 누구인가요",
        }
    ),
    QuickAnswerIntent.TECH_STACK: frozenset(
        {
            "사용 기술",
            "기술 스택",
            "어떤 기술을 사용했어",
            "어떤 기술을 사용했나요",
            "무슨 기술로 만들었어",
            "무슨 기술로 만들었나요",
        }
    ),
}


def _quick_answer(
    question: str, facts: ProjectFacts | None
) -> QuickAnswerResult | None:
    if facts is None:
        return None
    normalized = re.sub(
        r"\s+",
        " ",
        unicodedata.normalize("NFKC", question).strip().lower(),
    ).rstrip(" ?!.。！？")
    values = {
        QuickAnswerIntent.PROJECT_INTRODUCTION: ("프로젝트 소개", facts.introduction),
        QuickAnswerIntent.TARGET_AUDIENCE: ("대상 사용자", facts.target_audience),
        QuickAnswerIntent.TECH_STACK: ("사용 기술", facts.tech_stack),
    }
    for intent, questions in _QUICK_QUESTIONS.items():
        label, value = values[intent]
        if normalized in questions and value is not None:
            return QuickAnswerResult(intent=intent, message=f"{label}: {value.strip()}")
    return None


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
    ) -> ContextBuildResult | NoReadyContextResult | QuickAnswerResult:
        # Agent membership/activity must fail closed before Embedding and search.
        # The Spring contract requires exactly one uncached lookup per question.
        agent = await self._agent_config_provider.get(
            booth_id=conversation.scope.booth_id, agent_id=conversation.scope.agent_id
        )
        quick_answer = _quick_answer(question, agent.project_facts)
        if quick_answer is not None:
            return quick_answer
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
