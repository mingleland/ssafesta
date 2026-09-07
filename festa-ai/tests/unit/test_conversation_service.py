"""Verify Conversation creation is Fail Closed on any Spring outcome but allow."""

from __future__ import annotations

from datetime import datetime, timezone

import fakeredis
import pytest

from app.clients.spring_booth_access import BoothAccessResult
from app.models.conversation import Conversation
from app.repositories.conversation_repository import ConversationRepository
from app.services.conversation_service import (
    BoothAccessDenied,
    ConversationCreationFailed,
    ConversationService,
)
from app.services.stream_service import ConversationOwnershipMismatch
from tests.fakes.spring_booth_access import FakeSpringBoothAccessClient


def _service(spring_client: FakeSpringBoothAccessClient) -> ConversationService:
    repository = ConversationRepository(fakeredis.FakeAsyncRedis(), ttl_seconds=1800)
    return ConversationService(
        spring_client=spring_client,
        repository=repository,
        ttl_seconds=1800,
        clock=lambda: datetime(2026, 9, 3, 11, 40, tzinfo=timezone.utc),
        id_factory=lambda: "conv_fixed",
    )


@pytest.mark.asyncio
async def test_create_saves_conversation_when_spring_allows() -> None:
    spring_client = FakeSpringBoothAccessClient()
    spring_client.result = BoothAccessResult(
        allowed=True, lease_ends_at="2026-09-03T12:00:00+00:00", denial_code=None
    )
    service = _service(spring_client)

    conversation = await service.create(user_id=42, booth_id=7, agent_id=3)

    assert conversation.conversation_id == "conv_fixed"
    assert conversation.user_id == 42
    assert conversation.scope.booth_id == 7
    assert conversation.scope.agent_id == 3
    assert conversation.lease_ends_at == datetime(2026, 9, 3, 12, 0, tzinfo=timezone.utc)
    assert spring_client.calls == [(7, 3)]

    saved = await service._repository.get("conv_fixed")
    assert saved == conversation


@pytest.mark.asyncio
async def test_create_raises_booth_access_denied_when_spring_refuses() -> None:
    spring_client = FakeSpringBoothAccessClient()
    spring_client.result = BoothAccessResult(
        allowed=False, lease_ends_at=None, denial_code="BOOTH_LEASE_EXPIRED"
    )
    service = _service(spring_client)

    with pytest.raises(BoothAccessDenied) as exc_info:
        await service.create(user_id=42, booth_id=7, agent_id=3)

    assert exc_info.value.denial_code == "BOOTH_LEASE_EXPIRED"
    assert await service._repository.get("conv_fixed") is None


@pytest.mark.asyncio
async def test_create_fails_closed_when_spring_is_unavailable() -> None:
    spring_client = FakeSpringBoothAccessClient()
    spring_client.raise_unavailable = True
    service = _service(spring_client)

    with pytest.raises(ConversationCreationFailed):
        await service.create(user_id=42, booth_id=7, agent_id=3)

    assert await service._repository.get("conv_fixed") is None


async def _seed(service: ConversationService, *, user_id: int, lease_ends_at: datetime) -> str:
    conversation = Conversation.create(
        conversation_id="conv_fixed",
        user_id=user_id,
        booth_id=7,
        agent_id=3,
        lease_ends_at=lease_ends_at,
        now=datetime(2026, 9, 3, 11, 40, tzinfo=timezone.utc),
        ttl_seconds=1800,
    )
    await service._repository.save(conversation)
    return conversation.conversation_id


@pytest.mark.asyncio
async def test_close_deletes_the_owners_conversation() -> None:
    spring_client = FakeSpringBoothAccessClient()
    service = _service(spring_client)
    conversation_id = await _seed(
        service, user_id=42, lease_ends_at=datetime(2026, 9, 3, 12, 40, tzinfo=timezone.utc)
    )

    await service.close(conversation_id=conversation_id, user_id=42)

    assert await service._repository.get(conversation_id) is None
    # Article 3 — deleting raw text must not depend on Spring being reachable.
    assert spring_client.calls == []


@pytest.mark.asyncio
async def test_close_deletes_even_when_the_lease_already_expired() -> None:
    """An expired Lease must not strand raw text in Redis (D11 over FR-026)."""
    service = _service(FakeSpringBoothAccessClient())
    conversation_id = await _seed(
        service, user_id=42, lease_ends_at=datetime(2026, 9, 3, 10, 0, tzinfo=timezone.utc)
    )

    await service.close(conversation_id=conversation_id, user_id=42)

    assert await service._repository.get(conversation_id) is None


@pytest.mark.asyncio
async def test_close_refuses_another_users_conversation_and_keeps_it() -> None:
    service = _service(FakeSpringBoothAccessClient())
    conversation_id = await _seed(
        service, user_id=42, lease_ends_at=datetime(2026, 9, 3, 12, 40, tzinfo=timezone.utc)
    )

    with pytest.raises(ConversationOwnershipMismatch):
        await service.close(conversation_id=conversation_id, user_id=99)

    assert await service._repository.get(conversation_id) is not None
