"""Fetch one Agent's prompt configuration (role/tone/system prompt) by boothId+agentId.

Spring owns this data (`AiAgent`), but no internal API exposes it to FastAPI
yet — that is `S15P21A604-399` (`[BE] Agent 추론 설정 조회 내부 API 구현`, To Do as
of this ticket). `S15P21A604-140` only needs *something* implementing this
Protocol to wire the message-streaming endpoint end to end, so `MockAgentConfigProvider`
stands in until 399 ships a real Spring-backed implementation.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from app.services.context_service import AgentPromptConfig


@runtime_checkable
class AgentConfigProvider(Protocol):
    """Resolve the Agent settings `PromptBuilder` needs for one boothId+agentId."""

    async def get(self, *, booth_id: int, agent_id: int) -> AgentPromptConfig: ...


class MockAgentConfigProvider:
    """Deterministic placeholder Agent config — no cross-service call.

    Returns the same generic guide persona for every booth/agent until
    `S15P21A604-399` lands and a real Spring-backed provider replaces this.
    """

    async def get(self, *, booth_id: int, agent_id: int) -> AgentPromptConfig:
        return AgentPromptConfig(
            role="GUIDE",
            tone="FRIENDLY",
            response_length="MEDIUM",
            system_prompt="부스를 방문한 사용자의 질문에 문서를 근거로 안내한다.",
            forbidden_topics=(),
        )
