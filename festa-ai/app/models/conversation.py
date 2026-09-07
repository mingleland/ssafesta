"""Conversation domain objects for spec 008 (Redis-backed, 30-minute TTL).

Only the pieces `S15P21A604-126` needs — creation and its Scope snapshot.
`ConversationTurn`/`StreamAttempt`/SSE event models belong to the
messaging/streaming tickets (129/140) and are added there.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Literal

ConversationStatus = Literal["ACTIVE", "STREAMING", "CLOSED"]


@dataclass(frozen=True, slots=True)
class ConversationScope:
    """Immutable `boothId + agentId` snapshot — the only source of search scope.

    Never reconstructed from client input after creation (data-model.md).
    """

    booth_id: int
    agent_id: int


@dataclass(frozen=True, slots=True)
class Conversation:
    conversation_id: str
    user_id: int
    scope: ConversationScope
    lease_ends_at: datetime
    status: ConversationStatus
    last_activity_at: datetime
    expires_at: datetime

    @classmethod
    def create(
        cls,
        *,
        conversation_id: str,
        user_id: int,
        booth_id: int,
        agent_id: int,
        lease_ends_at: datetime,
        now: datetime,
        ttl_seconds: int,
    ) -> "Conversation":
        return cls(
            conversation_id=conversation_id,
            user_id=user_id,
            scope=ConversationScope(booth_id=booth_id, agent_id=agent_id),
            lease_ends_at=lease_ends_at,
            status="ACTIVE",
            last_activity_at=now,
            expires_at=now + timedelta(seconds=ttl_seconds),
        )
