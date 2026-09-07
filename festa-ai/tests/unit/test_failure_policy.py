"""알려진 처리 실패를 Spring `failed` 호출용 (code, retryable)로 매핑한다 (T021 최소 범위).

document-result-api.yaml의 `failureCode`는 최대 50자 문자열이며 분기 근거는
`retryable`뿐이다 — 사람이 읽을 사유는 Spring이 code를 번역해 붙인다 (FR-007).
"""

from __future__ import annotations

from app.providers.document_parser import DocumentParseError, ScannedDocumentError
from app.providers.embedding import EmbeddingProvider  # noqa: F401  (protocol 문서화용)
from app.providers.managed_embedding import ManagedEmbeddingError
from app.providers.storage import ObjectNotFoundError, ObjectStorageError
from app.services.document_processing_service import SourceHashMismatchError
from app.services.failure_policy import classify_failure


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


def test_unknown_exception_falls_back_to_unexpected_error() -> None:
    outcome = classify_failure(RuntimeError("boom"))

    assert outcome.code == "UNEXPECTED_ERROR"
    assert outcome.retryable is False
