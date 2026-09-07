"""Verify Conversation creation is Fail Closed on any Spring outcome but allow."""

from __future__ import annotations

from datetime import datetime, timezone

import fakeredis
import pytest

from app.clients.spring_booth_access import BoothAccessResult
from app.repositories.conversation_repository import ConversationRepository
from app.services.conversation_service import (
    BoothAccessDenied,
    ConversationCreationFailed,
    ConversationService,
)
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
