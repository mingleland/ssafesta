"""Verify the document-processing endpoint maps repository results to its HTTP contract."""

from __future__ import annotations

from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.dependencies.internal_auth import require_spring_service_token
from app.api.errors import ApiError, api_error_handler
from app.api.v1.documents import get_document_job_repository, router
from app.services.document_snapshot_validator import SnapshotConflictError
from tests.unit.test_document_schemas import VALID_REQUEST


class FakeRepository:
    def __init__(self, *, existing: bool = False, conflict: bool = False) -> None:
        self.existing = existing
        self.conflict = conflict

    async def enqueue(self, _snapshot, *, max_retries: int):
        assert max_retries == 3
        if self.conflict:
            raise SnapshotConflictError()
        return SimpleNamespace(
            job=SimpleNamespace(id=123, document_id=42, status="QUEUED"),
            existing=self.existing,
        )


def _client(repository: FakeRepository) -> TestClient:
    app = FastAPI()
    app.state.settings = SimpleNamespace(
        internal_spring_to_ai_tokens=["spring-token"],
        job_max_retries=3,
    )
    app.add_exception_handler(ApiError, api_error_handler)
    app.include_router(router, prefix="/ai/v1")
    app.dependency_overrides[get_document_job_repository] = lambda: repository
    return TestClient(app)


def test_new_job_returns_202_and_existing_false() -> None:
    response = _client(FakeRepository()).post(
        "/ai/v1/documents/process",
        json=VALID_REQUEST,
        headers={"Authorization": "Bearer spring-token"},
    )

    assert response.status_code == 202
    assert response.json() == {
        "jobId": "job_123",
        "documentId": 42,
        "status": "QUEUED",
        "existing": False,
    }


def test_existing_job_returns_202_and_existing_true() -> None:
    response = _client(FakeRepository(existing=True)).post(
        "/ai/v1/documents/process",
        json=VALID_REQUEST,
        headers={"Authorization": "Bearer spring-token"},
    )

    assert response.status_code == 202
    assert response.json()["existing"] is True


def test_snapshot_conflict_returns_409() -> None:
    response = _client(FakeRepository(conflict=True)).post(
        "/ai/v1/documents/process",
        json=VALID_REQUEST,
        headers={"Authorization": "Bearer spring-token"},
    )

    assert response.status_code == 409
    assert response.json() == {
        "code": "DOCUMENT_SNAPSHOT_CONFLICT",
        "message": "이미 접수된 활성 Job의 문서 정보와 요청이 일치하지 않습니다.",
    }

