"""Verify Conversation creation sets the 30-minute TTL and ACTIVE status."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.models.conversation import (
    MAX_STORED_TURNS,
    Conversation,
    ConversationScope,
    ConversationTurn,
    TokenEvent,
)


def test_create_sets_active_status_and_sliding_expiry() -> None:
    now = datetime(2026, 9, 3, 11, 40, tzinfo=timezone.utc)
    lease_ends_at = datetime(2026, 9, 3, 12, 0, tzinfo=timezone.utc)

    conversation = Conversation.create(
        conversation_id="conv_abc",
        user_id=42,
        booth_id=7,
        agent_id=3,
        lease_ends_at=lease_ends_at,
        now=now,
        ttl_seconds=1800,
    )

    assert conversation.conversation_id == "conv_abc"
    assert conversation.user_id == 42
    assert conversation.scope == ConversationScope(booth_id=7, agent_id=3)
    assert conversation.lease_ends_at == lease_ends_at
    assert conversation.status == "ACTIVE"
    assert conversation.last_activity_at == now
    assert conversation.expires_at == now + timedelta(seconds=1800)


def test_conversation_is_immutable() -> None:
    now = datetime(2026, 9, 3, 11, 40, tzinfo=timezone.utc)
    conversation = Conversation.create(
        conversation_id="conv_abc",
        user_id=42,
        booth_id=7,
        agent_id=3,
        lease_ends_at=now,
        now=now,
        ttl_seconds=1800,
    )

    with pytest.raises(AttributeError):
        conversation.status = "CLOSED"  # type: ignore[misc]


def _turn(question: str = "질문", answer: str = "답변") -> ConversationTurn:
    return ConversationTurn(
        request_id="req_1",
        user_message_id="umsg_1",
        assistant_message_id="amsg_1",
        question=question,
        answer=answer,
        sources=(),
        created_at=datetime(2026, 9, 6, 0, 0, tzinfo=timezone.utc),
    )


def test_conversation_turn_rejects_blank_question_or_answer() -> None:
    with pytest.raises(ValueError):
        _turn(question="   ")
    with pytest.raises(ValueError):
        _turn(answer="")


def test_record_turn_updates_activity_and_sliding_expiry() -> None:
    created = datetime(2026, 9, 3, 11, 40, tzinfo=timezone.utc)
    conversation = Conversation.create(
        conversation_id="conv_abc",
        user_id=42,
        booth_id=7,
        agent_id=3,
        lease_ends_at=created + timedelta(hours=1),
        now=created,
        ttl_seconds=1800,
    )

    later = created + timedelta(minutes=5)
    updated = conversation.record_turn(_turn(), now=later, ttl_seconds=1800)

    assert updated.turns == (_turn(),)
    assert updated.last_activity_at == later
    assert updated.expires_at == later + timedelta(seconds=1800)
    # Original instance is untouched — Conversation stays immutable.
    assert conversation.turns == ()


def test_record_turn_keeps_only_the_most_recent_max_stored_turns() -> None:
    now = datetime(2026, 9, 3, 11, 40, tzinfo=timezone.utc)
    conversation = Conversation.create(
        conversation_id="conv_abc",
        user_id=42,
        booth_id=7,
        agent_id=3,
        lease_ends_at=now + timedelta(hours=1),
        now=now,
        ttl_seconds=1800,
    )

    for index in range(MAX_STORED_TURNS + 2):
        conversation = conversation.record_turn(
            _turn(question=f"q{index}", answer=f"a{index}"), now=now, ttl_seconds=1800
        )

    assert len(conversation.turns) == MAX_STORED_TURNS
    assert conversation.turns[0].question == "q2"
    assert conversation.turns[-1].question == f"q{MAX_STORED_TURNS + 1}"


def test_token_event_rejects_empty_delta() -> None:
    with pytest.raises(ValueError):
        TokenEvent(delta="")
