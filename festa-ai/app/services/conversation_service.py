"""Conversation creation — Fail Closed on anything but an explicit Spring allow.

FR-024/FR-025: any outcome other than a parsed `allowed:true` (denial or
Spring being unreachable after the client's own retry) must refuse to create
the Conversation and must not touch retrieval or the LLM. This module only
covers creation; get/close/idle-expiry belongs to 127.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from datetime import datetime, timezone

from app.clients.spring_booth_access import SpringBoothAccessUnavailable
from app.models.conversation import Conversation
from app.repositories.conversation_repository import ConversationRepository


class BoothAccessDenied(Exception):
    """Spring answered but refused — Lease invalid, Agent not in Booth, or INACTIVE."""

    def __init__(self, denial_code: str) -> None:
        super().__init__(denial_code)
        self.denial_code = denial_code


class ConversationCreationFailed(Exception):
    """Spring did not answer in time — Fail Closed per FR-025."""


def _default_id_factory() -> str:
    return f"conv_{uuid.uuid4().hex}"


def _default_clock() -> datetime:
    return datetime.now(timezone.utc)


class ConversationService:
    def __init__(
        self,
        *,
        spring_client,
        repository: ConversationRepository,
        ttl_seconds: int,
        clock: Callable[[], datetime] = _default_clock,
        id_factory: Callable[[], str] = _default_id_factory,
    ) -> None:
        self._spring_client = spring_client
        self._repository = repository
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._id_factory = id_factory

    async def create(self, *, user_id: int, booth_id: int, agent_id: int) -> Conversation:
        try:
            result = await self._spring_client.check(booth_id=booth_id, agent_id=agent_id)
        except SpringBoothAccessUnavailable as exc:
            raise ConversationCreationFailed(
                "Spring booth-access validation unavailable"
            ) from exc

        if not result.allowed:
            assert result.denial_code is not None
            raise BoothAccessDenied(result.denial_code)

        assert result.lease_ends_at is not None
        conversation = Conversation.create(
            conversation_id=self._id_factory(),
            user_id=user_id,
            booth_id=booth_id,
            agent_id=agent_id,
            lease_ends_at=datetime.fromisoformat(result.lease_ends_at),
            now=self._clock(),
            ttl_seconds=self._ttl_seconds,
        )
        await self._repository.save(conversation)
        return conversation
