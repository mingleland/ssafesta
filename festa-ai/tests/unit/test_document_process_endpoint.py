"""무상태 문서 처리 접수 endpoint 테스트 (S15P21A604-124, S15P21A604-449 이후 계약).

FastAPI는 Job을 저장하지 않으므로 `startDocumentProcessing`은 202를 즉시 반환하고
실제 처리는 background task로 넘긴다. 같은 `jobId+attemptNo`가 아직 처리 중일 때
재전송되면 새 task를 만들지 않는 것으로 멱등을 보장한다 (요청 자체는 항상 202).
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI

from app.api.errors import ApiError, api_error_handler
from app.api.v1.documents import get_document_processing_orchestrator, router
from tests.unit.test_document_schemas import VALID_REQUEST

AUTH_HEADERS = {"Authorization": "Bearer spring-token"}


class FakeOrchestrator:
    def __init__(self) -> None:
        self.calls: list[object] = []
        self._release = asyncio.Event()

    async def run(self, snapshot: object) -> None:
        self.calls.append(snapshot)
        await self._release.wait()

    def release(self) -> None:
        self._release.set()


def _app(orchestrator: FakeOrchestrator) -> FastAPI:
    app = FastAPI()
    app.state.settings = SimpleNamespace(
        internal_spring_to_ai_tokens=["spring-token"],
    )
    app.state.in_flight_document_jobs = set()
    app.add_exception_handler(ApiError, api_error_handler)
    app.include_router(router, prefix="/ai/v1")
    app.dependency_overrides[get_document_processing_orchestrator] = lambda: orchestrator
    return app


@pytest.mark.asyncio
async def test_accepts_processing_and_schedules_orchestrator() -> None:
    orchestrator = FakeOrchestrator()
    transport = httpx.ASGITransport(app=_app(orchestrator))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/ai/v1/documents/process", json=VALID_REQUEST, headers=AUTH_HEADERS
        )
        assert response.status_code == 202
        assert response.content == b""

        await asyncio.sleep(0)
        assert len(orchestrator.calls) == 1
        snapshot = orchestrator.calls[0]
        assert snapshot.job_id == VALID_REQUEST["jobId"]
        assert snapshot.attempt_no == VALID_REQUEST["attemptNo"]
        assert snapshot.document_id == VALID_REQUEST["documentId"]
        assert snapshot.source_hash == VALID_REQUEST["sourceHash"]
        orchestrator.release()


@pytest.mark.asyncio
async def test_duplicate_job_id_and_attempt_no_does_not_start_second_run() -> None:
    orchestrator = FakeOrchestrator()
    transport = httpx.ASGITransport(app=_app(orchestrator))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        first = await client.post(
            "/ai/v1/documents/process", json=VALID_REQUEST, headers=AUTH_HEADERS
        )
        await asyncio.sleep(0)
        second = await client.post(
            "/ai/v1/documents/process", json=VALID_REQUEST, headers=AUTH_HEADERS
        )
        await asyncio.sleep(0)

        assert first.status_code == 202
        assert second.status_code == 202
        assert len(orchestrator.calls) == 1
        orchestrator.release()


@pytest.mark.asyncio
async def test_rejects_missing_service_token() -> None:
    transport = httpx.ASGITransport(app=_app(FakeOrchestrator()))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/ai/v1/documents/process", json=VALID_REQUEST)

    assert response.status_code == 401
