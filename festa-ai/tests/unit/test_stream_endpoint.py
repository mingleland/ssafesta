"""Verify the message-streaming endpoint maps authorize() outcomes to its HTTP contract."""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.errors import ApiError, api_error_handler, request_validation_error_handler
from app.api.v1.conversations import get_stream_service, router
from app.core.auth import AuthenticatedMember, require_member
from app.models.conversation import Conversation
from app.services.stream_service import (
    BoothLeaseExpired,
    ConversationNotFound,
    ConversationOwnershipMismatch,
)
from fastapi.exceptions import RequestValidationError

_MEMBER = AuthenticatedMember(user_id=42)


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


class FakeStreamService:
    def __init__(self, *, conversation=None, authorize_error: Exception | None = None):
        self._conversation = conversation
        self._authorize_error = authorize_error
        self.stream_calls: list[dict[str, object]] = []

    async def authorize(self, *, conversation_id: str, user_id: int) -> Conversation:
        if self._authorize_error is not None:
            raise self._authorize_error
        assert self._conversation is not None
        return self._conversation

    async def stream(self, *, conversation: Conversation, question: str):
        self.stream_calls.append({"conversation": conversation, "question": question})
        yield "event: start\ndata: {}\n\n"
        yield "event: done\ndata: {}\n\n"


def _client(service: FakeStreamService) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, request_validation_error_handler)
    app.include_router(router, prefix="/ai/v1")
    app.dependency_overrides[require_member] = lambda: _MEMBER
    app.dependency_overrides[get_stream_service] = lambda: service
    return TestClient(app)


def test_authorized_request_streams_sse_body() -> None:
    service = FakeStreamService(conversation=_conversation())

    response = _client(service).post(
        "/ai/v1/conversations/conv_abc/messages", json={"question": "안녕하세요"}
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: start" in response.text
    assert "event: done" in response.text
    assert service.stream_calls == [{"conversation": _conversation(), "question": "안녕하세요"}]


def test_missing_conversation_returns_404() -> None:
    service = FakeStreamService(authorize_error=ConversationNotFound("conv_abc"))

    response = _client(service).post(
        "/ai/v1/conversations/conv_abc/messages", json={"question": "안녕하세요"}
    )

    assert response.status_code == 404
    assert response.json()["code"] == "CONVERSATION_NOT_FOUND"


def test_other_users_conversation_returns_403() -> None:
    service = FakeStreamService(authorize_error=ConversationOwnershipMismatch("conv_abc"))

    response = _client(service).post(
        "/ai/v1/conversations/conv_abc/messages", json={"question": "안녕하세요"}
    )

    assert response.status_code == 403
    assert response.json()["code"] == "CONVERSATION_OWNERSHIP_MISMATCH"


def test_expired_lease_returns_403() -> None:
    service = FakeStreamService(authorize_error=BoothLeaseExpired("conv_abc"))

    response = _client(service).post(
        "/ai/v1/conversations/conv_abc/messages", json={"question": "안녕하세요"}
    )

    assert response.status_code == 403
    assert response.json()["code"] == "BOOTH_LEASE_EXPIRED"


def test_blank_question_is_rejected() -> None:
    service = FakeStreamService(conversation=_conversation())

    response = _client(service).post(
        "/ai/v1/conversations/conv_abc/messages", json={"question": ""}
    )

    assert response.status_code == 422
    assert service.stream_calls == []


def test_question_over_2000_characters_is_rejected() -> None:
    service = FakeStreamService(conversation=_conversation())

    response = _client(service).post(
        "/ai/v1/conversations/conv_abc/messages", json={"question": "x" * 2001}
    )

    assert response.status_code == 422
    assert service.stream_calls == []
