"""Verify the Conversation endpoint maps service outcomes to its HTTP contract."""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.errors import ApiError, api_error_handler
from app.api.v1.conversations import get_conversation_service, router
from app.core.auth import AuthenticatedMember, require_member
from app.models.conversation import Conversation
from app.services.conversation_service import (
    BoothAccessDenied,
    ConversationCreationFailed,
)

_MEMBER = AuthenticatedMember(user_id=42)


class FakeConversationService:
    def __init__(self, *, conversation: Conversation | None = None, error: Exception | None = None):
        self.conversation = conversation
        self.error = error
        self.calls: list[dict[str, int]] = []

    async def create(self, *, user_id: int, booth_id: int, agent_id: int) -> Conversation:
        self.calls.append({"user_id": user_id, "booth_id": booth_id, "agent_id": agent_id})
        if self.error is not None:
            raise self.error
        assert self.conversation is not None
        return self.conversation


def _client(service: FakeConversationService) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.include_router(router, prefix="/ai/v1")
    app.dependency_overrides[require_member] = lambda: _MEMBER
    app.dependency_overrides[get_conversation_service] = lambda: service
    return TestClient(app)


def _conversation() -> Conversation:
    now = datetime(2026, 9, 3, 11, 40, tzinfo=timezone.utc)
    return Conversation.create(
        conversation_id="conv_abc",
        user_id=42,
        booth_id=7,
        agent_id=3,
        lease_ends_at=now,
        now=now,
        ttl_seconds=1800,
    )


def test_create_returns_201_with_id_and_expiry() -> None:
    service = FakeConversationService(conversation=_conversation())

    response = _client(service).post(
        "/ai/v1/conversations", json={"boothId": 7, "agentId": 3}
    )

    assert response.status_code == 201
    assert response.json() == {
        "conversationId": "conv_abc",
        "expiresAt": "2026-09-03T12:10:00Z",
    }
    assert service.calls == [{"user_id": 42, "booth_id": 7, "agent_id": 3}]


def test_booth_access_denied_returns_403_with_denial_code() -> None:
    service = FakeConversationService(error=BoothAccessDenied("BOOTH_LEASE_EXPIRED"))

    response = _client(service).post(
        "/ai/v1/conversations", json={"boothId": 7, "agentId": 3}
    )

    assert response.status_code == 403
    assert response.json()["code"] == "BOOTH_LEASE_EXPIRED"


def test_spring_unavailable_returns_503() -> None:
    service = FakeConversationService(error=ConversationCreationFailed("timeout"))

    response = _client(service).post(
        "/ai/v1/conversations", json={"boothId": 7, "agentId": 3}
    )

    assert response.status_code == 503
    assert response.json()["code"] == "SPRING_UNAVAILABLE"


def test_rejects_request_body_with_unknown_field() -> None:
    service = FakeConversationService(conversation=_conversation())

    response = _client(service).post(
        "/ai/v1/conversations",
        json={"boothId": 7, "agentId": 3, "unexpected": "field"},
    )

    assert response.status_code == 422
