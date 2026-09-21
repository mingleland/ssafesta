"""알려진 문서 처리 실패를 Spring `failed` 호출의 (failureCode, retryable)로 옮긴다.

`failureCode`는 분기의 근거이고 사람이 읽을 사유는 Spring이 code를 번역해
붙인다 (document-result-api.yaml `FailedRequest.message` 설명, FR-007) — 여기서
내부 예외 메시지나 Stack Trace를 `code`에 담지 않는다.

`S15P21A604-124`가 처리 중 실제로 만나는 실패만 다룬다. 새 예외 유형이
추가되면 이 매핑도 같이 넓힌다 (T021 최소 범위).
"""

from __future__ import annotations

from dataclasses import dataclass

from app.clients.spring_booth_access import SpringBoothAccessUnavailable
from app.clients.spring_document_result import (
    SpringDocumentResultUnavailable,
    SpringDocumentResultValidationFailed,
)
from app.providers.document_parser import DocumentParseError, ScannedDocumentError
from app.providers.managed_embedding import ManagedEmbeddingError
from app.providers.storage import ObjectNotFoundError, ObjectStorageError
from app.services.document_processing_service import (
    BoothLeaseExpiredError,
    SourceHashMismatchError,
)


@dataclass(frozen=True, slots=True)
class FailureOutcome:
    code: str
    retryable: bool


_DETAIL_MAX_LENGTH = 200


def classify_failure(exc: Exception) -> FailureOutcome:
    if isinstance(exc, ManagedEmbeddingError):
        return FailureOutcome(code=exc.code, retryable=exc.retryable)
    if isinstance(exc, SourceHashMismatchError):
        return FailureOutcome(code="SOURCE_HASH_MISMATCH", retryable=False)
    if isinstance(exc, BoothLeaseExpiredError):
        return FailureOutcome(code="BOOTH_LEASE_EXPIRED", retryable=False)
    if isinstance(exc, SpringBoothAccessUnavailable):
        return FailureOutcome(code="BOOTH_ACCESS_CHECK_FAILED", retryable=True)
    if isinstance(exc, ScannedDocumentError):
        return FailureOutcome(code="UNSUPPORTED_SCAN_PDF", retryable=False)
    if isinstance(exc, DocumentParseError):
        return FailureOutcome(code="PARSE_FAILED", retryable=False)
    if isinstance(exc, ObjectNotFoundError):
        return FailureOutcome(code="SOURCE_NOT_FOUND", retryable=False)
    if isinstance(exc, ObjectStorageError):
        return FailureOutcome(code="STORAGE_UNAVAILABLE", retryable=True)
    if isinstance(exc, SpringDocumentResultValidationFailed):
        # Spring 이 결과 요청을 계약 위반으로 거절했다(400). 재시도해도 같으므로 DEAD 이지만,
        # "무엇이 거절됐는지" 는 남아야 한다 — finalize 의 projectFacts 거절이 UNEXPECTED_ERROR
        # 로만 남아 두 환경의 문서 처리가 통째로 죽은 원인을 하루 넘게 못 찾았다 (S15P21A604-939).
        return FailureOutcome(code="CONTRACT_REJECTED", retryable=False)
    if isinstance(exc, SpringDocumentResultUnavailable):
        return FailureOutcome(code="SPRING_RESULT_UNAVAILABLE", retryable=True)
    if isinstance(exc, ValueError):
        return FailureOutcome(code="CHUNKING_FAILED", retryable=False)
    return FailureOutcome(code="UNEXPECTED_ERROR", retryable=False)


def describe_failure(exc: Exception) -> str:
    """운영자가 로그·`last_error` 에서 읽을 짧은 사유. 문서 본문·비밀값은 싣지 않는다.

    Spring 오류 봉투에서는 `code` 와 `errors[].field`(계약 필드 이름)만 뽑는다 — 메시지 문장은
    문서 내용을 되비칠 수 있어 싣지 않는다. 그 밖의 예외는 클래스 이름만 남긴다.
    """
    if isinstance(exc, SpringDocumentResultValidationFailed) and isinstance(exc.body, dict):
        code = exc.body.get("code")
        fields = [
            str(item.get("field"))
            for item in exc.body.get("errors") or []
            if isinstance(item, dict) and item.get("field")
        ]
        detail = f"{type(exc).__name__}: code={code} fields={','.join(fields) or '-'}"
    else:
        detail = type(exc).__name__
    return detail[:_DETAIL_MAX_LENGTH]
