"""문서 처리 endpoint가 Spring attempt를 Worker supervisor에 멱등 전달하는지 검증한다."""

from __future__ import annotations

import httpx
import pytest
from fastapi import FastAPI

from app.api.errors import ApiError, api_error_handler
from app.api.v1.documents import get_document_task_supervisor, router
from app.workers.document_task_supervisor import SubmitResult, SupervisorClosedError
from tests.unit.test_document_schemas import VALID_REQUEST

AUTH_HEADERS = {"Authorization": "Bearer spring-token"}


class FakeSupervisor:
    def __init__(self, result: SubmitResult = SubmitResult.ACCEPTED) -> None:
        self.result = result
        self.attempts: list[object] = []

    def submit(self, attempt: object) -> SubmitResult:
        self.attempts.append(attempt)
        return self.result


class ClosedSupervisor(FakeSupervisor):
    def submit(self, attempt: object) -> SubmitResult:
        raise SupervisorClosedError("draining")


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
async def test_accepts_processing_and_submits_snapshot() -> None:
    supervisor = FakeSupervisor()
    transport = httpx.ASGITransport(app=_app(supervisor))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/ai/v1/documents/process", json=VALID_REQUEST, headers=AUTH_HEADERS
        )

    assert response.status_code == 202
    assert response.content == b""
    assert len(supervisor.attempts) == 1
    snapshot = supervisor.attempts[0]
    assert snapshot.job_id == VALID_REQUEST["jobId"]
    assert snapshot.attempt_no == VALID_REQUEST["attemptNo"]
    assert snapshot.document_id == VALID_REQUEST["documentId"]
    assert snapshot.source_hash == VALID_REQUEST["sourceHash"]


@pytest.mark.asyncio
async def test_duplicate_attempt_is_idempotently_accepted() -> None:
    supervisor = FakeSupervisor(SubmitResult.DUPLICATE)
    transport = httpx.ASGITransport(app=_app(supervisor))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/ai/v1/documents/process", json=VALID_REQUEST, headers=AUTH_HEADERS
        )

    assert response.status_code == 202
    assert response.content == b""
    assert len(supervisor.attempts) == 1


@pytest.mark.asyncio
async def test_rejects_missing_service_token() -> None:
    transport = httpx.ASGITransport(app=_app(FakeSupervisor()))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/ai/v1/documents/process", json=VALID_REQUEST)

    assert response.status_code == 401


@pytest.mark.asyncio
async def test_rejects_new_attempt_while_worker_is_draining() -> None:
    transport = httpx.ASGITransport(app=_app(ClosedSupervisor()))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/ai/v1/documents/process", json=VALID_REQUEST, headers=AUTH_HEADERS
        )

    assert response.status_code == 503
    assert response.headers["Retry-After"] == "1"
    assert response.json()["code"] == "WORKER_DRAINING"
