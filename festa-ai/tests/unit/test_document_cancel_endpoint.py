"""문서 처리 취소 endpoint가 supervisor.cancel을 멱등 호출하는지 검증한다."""

from __future__ import annotations

import httpx
import pytest
from fastapi import FastAPI

from app.api.errors import ApiError, api_error_handler
from app.api.v1.documents import get_document_task_supervisor, router

AUTH_HEADERS = {"Authorization": "Bearer spring-token"}
CANCEL_REQUEST = {"jobId": 41, "attemptNo": 3}


class FakeSupervisor:
    def __init__(self) -> None:
        self.cancelled: list[tuple[int, int]] = []

    def cancel(self, *, job_id: int, attempt_no: int) -> None:
        self.cancelled.append((job_id, attempt_no))


def _app(supervisor: FakeSupervisor) -> FastAPI:
    app = FastAPI()
    app.state.settings = type(
        "Settings", (), {"internal_spring_to_ai_tokens": ["spring-token"]}
    )()
    app.add_exception_handler(ApiError, api_error_handler)
    app.include_router(router, prefix="/ai/v1")
    app.dependency_overrides[get_document_task_supervisor] = lambda: supervisor
    return app


@pytest.mark.asyncio
async def test_cancel_forwards_job_and_attempt_and_returns_204() -> None:
    supervisor = FakeSupervisor()
    transport = httpx.ASGITransport(app=_app(supervisor))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/ai/v1/documents/cancel", json=CANCEL_REQUEST, headers=AUTH_HEADERS
        )

    assert response.status_code == 204
    assert response.content == b""
    assert supervisor.cancelled == [(41, 3)]


@pytest.mark.asyncio
async def test_cancel_is_idempotent_when_supervisor_finds_nothing() -> None:
    supervisor = FakeSupervisor()
    transport = httpx.ASGITransport(app=_app(supervisor))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        first = await client.post(
            "/ai/v1/documents/cancel", json=CANCEL_REQUEST, headers=AUTH_HEADERS
        )
        second = await client.post(
            "/ai/v1/documents/cancel", json=CANCEL_REQUEST, headers=AUTH_HEADERS
        )

    assert first.status_code == 204
    assert second.status_code == 204
    assert supervisor.cancelled == [(41, 3), (41, 3)]


@pytest.mark.asyncio
async def test_cancel_rejects_missing_service_token() -> None:
    transport = httpx.ASGITransport(app=_app(FakeSupervisor()))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/ai/v1/documents/cancel", json=CANCEL_REQUEST)

    assert response.status_code == 401
