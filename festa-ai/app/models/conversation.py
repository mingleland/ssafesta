"""Conversation domain objects for spec 008 (Redis-backed, 30-minute TTL).

`S15P21A604-126` added creation and the Scope snapshot. `S15P21A604-140` adds
`ConversationTurn` and the C-07 SSE event models — `StreamAttempt` bookkeeping
(timeout phase, first-token latency) stays with the timeout ticket (141) that
actually consumes it.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta
from typing import Literal

ConversationStatus = Literal["ACTIVE", "STREAMING", "CLOSED"]

# data-model.md "ConversationTurn": 최신 왕복 6회만 원문으로 유지한다.
MAX_STORED_TURNS = 6


@dataclass(frozen=True, slots=True)
class ConversationScope:
    """Immutable `boothId + agentId` snapshot — the only source of search scope.

    Never reconstructed from client input after creation (data-model.md).
    """

    booth_id: int
    agent_id: int


@dataclass(frozen=True, slots=True)
class SourceCitation:
    """One `documentId + chunkId` cited by a completed answer (no chunk text)."""

    document_id: int
    chunk_id: int
    title: str


@dataclass(frozen=True, slots=True)
class ConversationTurn:
    """One confirmed question/answer round — only written after `done` (data-model.md)."""

    request_id: str
    user_message_id: str
    assistant_message_id: str
    question: str
    answer: str
    sources: tuple[SourceCitation, ...]
    created_at: datetime

    def __post_init__(self) -> None:
        if not self.question.strip() or not self.answer.strip():
            raise ValueError("ConversationTurn question/answer must not be blank")


@dataclass(frozen=True, slots=True)
class Conversation:
    conversation_id: str
    user_id: int
    scope: ConversationScope
    lease_ends_at: datetime
    status: ConversationStatus
    last_activity_at: datetime
    expires_at: datetime
    turns: tuple[ConversationTurn, ...] = field(default=())

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
            turns=(),
        )

    def record_turn(
        self, turn: ConversationTurn, *, now: datetime, ttl_seconds: int
    ) -> "Conversation":
        """Append one completed turn with the sliding TTL a successful message earns."""
        updated_turns = (*self.turns, turn)[-MAX_STORED_TURNS:]
        return replace(
            self,
            turns=updated_turns,
            last_activity_at=now,
            expires_at=now + timedelta(seconds=ttl_seconds),
        )


# --- C-07 SSE envelope payloads (spec 008 FR-005a) --------------------------

SseEventType = Literal["start", "token", "source", "done", "error"]
TimeoutPhase = Literal["FIRST_TOKEN", "TOTAL_RESPONSE"]


@dataclass(frozen=True, slots=True)
class StartEvent:
    """First event of every stream. No additional fields."""


@dataclass(frozen=True, slots=True)
class TokenEvent:
    delta: str

    def __post_init__(self) -> None:
        if not self.delta:
            raise ValueError("TokenEvent delta must not be empty")


@dataclass(frozen=True, slots=True)
class SourceEvent:
    """Emitted once per unique `documentId + chunkId` (P0: no `sourceUrl`)."""

    document_id: int
    chunk_id: int
    title: str


@dataclass(frozen=True, slots=True)
class DoneEvent:
    """Terminal success event. P0 keeps `handoffRecommended` in the type without UI behavior."""

    handoff_recommended: bool = False


@dataclass(frozen=True, slots=True)
class ErrorEvent:
    """Terminal failure event. `timeoutPhase` distinguishes first-token vs total-response timeouts."""

    code: str
    message: str
    retryable: bool
    retry_after_seconds: int | None = None
    timeout_phase: TimeoutPhase | None = None
