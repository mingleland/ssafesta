"""Verify document API DTOs against the spec 007 OpenAPI constraints.

S15P21A604-449 이후 계약(`document-processing-api.yaml` v0.6.0): FastAPI는 Job을
소유하지 않으므로 요청에 `jobId`·`attemptNo`가 실리고, 202 Accepted는 본문이
없다 — `ProcessDocumentResponse`는 더 이상 계약에 없다.
"""

from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.api.errors import request_validation_error_handler
from app.api.schemas.documents import ProcessDocumentRequest


VALID_REQUEST = {
    "jobId": 501,
    "attemptNo": 0,
    "documentId": 42,
    "boothId": 10,
    "agentId": 7,
    "originalFilename": "guide.pdf",
    "contentType": "application/pdf",
    "fileSizeBytes": 1024,
    "storageProvider": "R2",
    "storageBucket": "festa-documents",
    "objectKey": "documents/42/source.pdf",
    "sourceHash": "a" * 64,
}


def test_process_document_request_accepts_contract_payload() -> None:
    request = ProcessDocumentRequest.model_validate(VALID_REQUEST)

    assert request.job_id == 501
    assert request.attempt_no == 0
    assert request.document_id == 42
    assert request.model_dump(by_alias=True) == VALID_REQUEST


def test_process_document_request_accepts_exact_20mb_limit() -> None:
    request = ProcessDocumentRequest.model_validate(
        {**VALID_REQUEST, "fileSizeBytes": 20_971_520}
    )

    assert request.file_size_bytes == 20_971_520


def test_process_document_request_accepts_zero_attempt_no() -> None:
    request = ProcessDocumentRequest.model_validate({**VALID_REQUEST, "attemptNo": 0})

    assert request.attempt_no == 0


def test_process_document_request_rejects_zero_byte_file() -> None:
    with pytest.raises(ValidationError):
        ProcessDocumentRequest.model_validate({**VALID_REQUEST, "fileSizeBytes": 0})


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("sourceHash", "A" * 64),
        ("objectKey", ""),
        ("fileSizeBytes", 20_971_521),
        ("contentType", "application/octet-stream"),
        ("storageProvider", "S3"),
        ("jobId", 0),
        ("attemptNo", -1),
    ],
)
def test_process_document_request_rejects_contract_violations(
    field: str, value: object
) -> None:
    payload = {**VALID_REQUEST, field: value}

    with pytest.raises(ValidationError):
        ProcessDocumentRequest.model_validate(payload)


def test_process_document_request_rejects_undeclared_fields() -> None:
    with pytest.raises(ValidationError):
        ProcessDocumentRequest.model_validate({**VALID_REQUEST, "unexpected": True})


def test_request_validation_error_uses_sanitized_contract_body() -> None:
    app = FastAPI()
    app.add_exception_handler(RequestValidationError, request_validation_error_handler)

    @app.post("/documents")
    async def create_document(_request: ProcessDocumentRequest) -> None:
        return None

    response = TestClient(app).post(
        "/documents",
        json={**VALID_REQUEST, "sourceHash": "SECRET_INVALID_HASH"},
    )

    assert response.status_code == 422
    assert response.json() == {
        "code": "INVALID_REQUEST",
        "message": "요청 형식이 올바르지 않습니다.",
    }
    assert "SECRET_INVALID_HASH" not in response.text
