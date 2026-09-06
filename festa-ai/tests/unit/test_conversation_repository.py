"""Verify Redis-backed Conversation storage sets TTL and round-trips fields."""

from __future__ import annotations

from datetime import datetime, timezone

import fakeredis
import pytest

from app.models.conversation import Conversation
from app.repositories.conversation_repository import ConversationRepository


@pytest.fixture
def repository() -> ConversationRepository:
    return ConversationRepository(fakeredis.FakeAsyncRedis(), ttl_seconds=1800)


def _conversation() -> Conversation:
    now = datetime(2026, 9, 3, 11, 40, tzinfo=timezone.utc)
    return Conversation.create(
        conversation_id="conv_abc",
        user_id=42,
        booth_id=7,
        agent_id=3,
        lease_ends_at=datetime(2026, 9, 3, 12, 0, tzinfo=timezone.utc),
        now=now,
        ttl_seconds=1800,
    )


@pytest.mark.asyncio
async def test_save_then_get_round_trips_all_fields(
    repository: ConversationRepository,
) -> None:
    conversation = _conversation()

    await repository.save(conversation)
    loaded = await repository.get(conversation.conversation_id)

    assert loaded == conversation


@pytest.mark.asyncio
async def test_get_returns_none_for_unknown_id(
    repository: ConversationRepository,
) -> None:
    assert await repository.get("conv_missing") is None


@pytest.mark.asyncio
async def test_save_sets_ttl_on_the_key(repository: ConversationRepository) -> None:
    conversation = _conversation()

    await repository.save(conversation)

    ttl = await repository._redis.ttl(f"conversation:{conversation.conversation_id}")
    assert 0 < ttl <= 1800
