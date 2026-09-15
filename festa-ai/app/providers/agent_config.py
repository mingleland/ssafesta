"""Define the boundary used to resolve one Agent's server-owned prompt settings."""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from app.services.context_service import AgentPromptConfig


@runtime_checkable
class AgentConfigProvider(Protocol):
    """Resolve the Agent settings `PromptBuilder` needs for one boothId+agentId."""

    async def get(self, *, booth_id: int, agent_id: int) -> AgentPromptConfig: ...
