"""Verify Conversation creation sets the 30-minute TTL and ACTIVE status."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.models.conversation import Conversation, ConversationScope


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
