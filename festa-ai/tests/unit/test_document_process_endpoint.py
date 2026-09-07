"""문서 처리 endpoint가 Spring attempt를 Worker supervisor에 멱등 전달하는지 검증한다."""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.errors import ApiError, api_error_handler
from app.api.v1.documents import get_document_task_supervisor, router
from app.workers.document_task_supervisor import SubmitResult
from tests.unit.test_document_schemas import VALID_REQUEST


class FakeSupervisor:
    def __init__(self, result: SubmitResult = SubmitResult.ACCEPTED) -> None:
        self.result = result
        self.attempts = []

    def submit(self, attempt):
        self.attempts.append(attempt)
        return self.result


def _client(supervisor: FakeSupervisor) -> TestClient:
    app = FastAPI()
    app.state.settings = type(
        "Settings", (), {"internal_spring_to_ai_tokens": ["spring-token"]}
    )()
    app.add_exception_handler(ApiError, api_error_handler)
    app.include_router(router, prefix="/ai/v1")
    app.dependency_overrides[get_document_task_supervisor] = lambda: supervisor
    return TestClient(app)


def test_new_attempt_returns_202_without_body() -> None:
    supervisor = FakeSupervisor()

    response = _client(supervisor).post(
        "/ai/v1/documents/process",
        json=VALID_REQUEST,
        headers={"Authorization": "Bearer spring-token"},
    )

    assert response.status_code == 202
    assert response.content == b""
    assert [(item.job_id, item.attempt_no) for item in supervisor.attempts] == [
        (123, 0)
    ]


def test_duplicate_attempt_is_idempotently_accepted() -> None:
    supervisor = FakeSupervisor(SubmitResult.DUPLICATE)

    response = _client(supervisor).post(
        "/ai/v1/documents/process",
        json=VALID_REQUEST,
        headers={"Authorization": "Bearer spring-token"},
    )

    assert response.status_code == 202
    assert response.content == b""
    assert len(supervisor.attempts) == 1
