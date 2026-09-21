"""알려진 처리 실패를 Spring `failed` 호출용 (code, retryable)로 매핑한다 (T021 최소 범위).

document-result-api.yaml의 `failureCode`는 최대 50자 문자열이며 분기 근거는
`retryable`뿐이다 — 사람이 읽을 사유는 Spring이 code를 번역해 붙인다 (FR-007).
"""

from __future__ import annotations

from app.clients.spring_booth_access import SpringBoothAccessUnavailable
from app.providers.document_parser import DocumentParseError, ScannedDocumentError
from app.providers.embedding import EmbeddingProvider  # noqa: F401  (protocol 문서화용)
from app.providers.managed_embedding import ManagedEmbeddingError
from app.providers.storage import ObjectNotFoundError, ObjectStorageError
from app.services.document_processing_service import (
    BoothLeaseExpiredError,
    SourceHashMismatchError,
)
from app.clients.spring_document_result import (
    SpringDocumentResultUnavailable,
    SpringDocumentResultValidationFailed,
)
from app.services.failure_policy import classify_failure, describe_failure


def test_source_hash_mismatch_is_not_retryable() -> None:
    outcome = classify_failure(SourceHashMismatchError("mismatch"))

    assert outcome.code == "SOURCE_HASH_MISMATCH"
    assert outcome.retryable is False


def test_scanned_pdf_is_not_retryable() -> None:
    outcome = classify_failure(ScannedDocumentError("scanned"))

    assert outcome.code == "UNSUPPORTED_SCAN_PDF"
    assert outcome.retryable is False


def test_generic_parse_error_is_not_retryable() -> None:
    outcome = classify_failure(DocumentParseError("broken"))

    assert outcome.code == "PARSE_FAILED"
    assert outcome.retryable is False


def test_object_not_found_is_not_retryable() -> None:
    outcome = classify_failure(ObjectNotFoundError("missing"))

    assert outcome.code == "SOURCE_NOT_FOUND"
    assert outcome.retryable is False


def test_generic_storage_error_is_retryable() -> None:
    outcome = classify_failure(ObjectStorageError("network blip"))

    assert outcome.code == "STORAGE_UNAVAILABLE"
    assert outcome.retryable is True


def test_managed_embedding_error_keeps_its_own_code_and_retryable() -> None:
    outcome = classify_failure(
        ManagedEmbeddingError("EMBEDDING_TIMEOUT", retryable=True)
    )

    assert outcome.code == "EMBEDDING_TIMEOUT"
    assert outcome.retryable is True


def test_no_chunks_produced_is_not_retryable() -> None:
    outcome = classify_failure(ValueError("문서에서 임베딩할 텍스트 청크를 만들지 못했습니다."))

    assert outcome.code == "CHUNKING_FAILED"
    assert outcome.retryable is False


def test_booth_lease_expired_is_not_retryable() -> None:
    outcome = classify_failure(BoothLeaseExpiredError("BOOTH_LEASE_EXPIRED"))

    assert outcome.code == "BOOTH_LEASE_EXPIRED"
    assert outcome.retryable is False


def test_booth_access_check_failure_is_retryable() -> None:
    outcome = classify_failure(SpringBoothAccessUnavailable("unreachable"))

    assert outcome.code == "BOOTH_ACCESS_CHECK_FAILED"
    assert outcome.retryable is True


def test_unknown_exception_falls_back_to_unexpected_error() -> None:
    outcome = classify_failure(RuntimeError("boom"))

    assert outcome.code == "UNEXPECTED_ERROR"
    assert outcome.retryable is False


# Spring 이 결과 요청을 계약 위반(400)으로 거절한 경우다. 재시도해도 같으므로 DEAD 이지만
# UNEXPECTED_ERROR 로 뭉개면 원인이 사라진다 — finalize 의 projectFacts 거절이 그랬다 (S15P21A604-939).
def test_contract_rejection_is_named_and_not_retryable() -> None:
    exc = SpringDocumentResultValidationFailed(
        {"code": "VALIDATION_FAILED", "errors": [{"rule": "FIELD_INVALID", "field": "projectFacts"}]}
    )
    outcome = classify_failure(exc)

    assert outcome.code == "CONTRACT_REJECTED"
    assert outcome.retryable is False


def test_spring_result_unavailable_is_retryable() -> None:
    outcome = classify_failure(SpringDocumentResultUnavailable("document-result returned status 503"))

    assert outcome.code == "SPRING_RESULT_UNAVAILABLE"
    assert outcome.retryable is True


def test_describe_failure_keeps_only_contract_code_and_fields() -> None:
    exc = SpringDocumentResultValidationFailed(
        {
            "code": "VALIDATION_FAILED",
            "message": "문서 본문 '비밀 기획서' 가 노출되면 안 된다",
            "errors": [{"rule": "FIELD_INVALID", "field": "projectFacts", "message": "계약에 없는 필드입니다."}],
        }
    )

    detail = describe_failure(exc)

    assert detail == "SpringDocumentResultValidationFailed: code=VALIDATION_FAILED fields=projectFacts"
    assert "비밀 기획서" not in detail
    assert "계약에 없는" not in detail


def test_describe_failure_truncates_and_names_unknown_exceptions() -> None:
    assert describe_failure(RuntimeError("x" * 1000)) == "RuntimeError"
    long_field = "f" * 500
    exc = SpringDocumentResultValidationFailed({"code": "VALIDATION_FAILED", "errors": [{"field": long_field}]})
    assert len(describe_failure(exc)) == 200
