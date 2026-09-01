"""문서 처리 서비스가 특정 object storage 공급자(R2·MinIO)에 의존하지 않게 하는 계약이다."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable


@dataclass(frozen=True, slots=True)
class ObjectMetadata:
    """HEAD 응답에서 얻는, 다운로드 전 원본 검증에 필요한 최소 정보다."""

    content_length: int
    content_type: str | None


class ObjectStorageError(Exception):
    """Provider 원문이나 Secret을 포함하지 않는 정규화 오류다."""


class ObjectNotFoundError(ObjectStorageError):
    """지정한 object_key가 버킷에 없다 (SOURCE_NOT_FOUND)."""


class ObjectVerificationError(ObjectStorageError):
    """다운로드한 원본이 기대한 크기 또는 SHA-256과 다르다 (SOURCE_HASH_MISMATCH)."""


@runtime_checkable
class ObjectStorage(Protocol):
    """R2·MinIO 등 S3-compatible 공급자에 균일하게 접근하는 최소 인터페이스."""

    def put_object(
        self, object_key: str, body: bytes, *, content_type: str | None = None
    ) -> None: ...

    def head_object(self, object_key: str) -> ObjectMetadata: ...

    def get_object(self, object_key: str) -> bytes: ...
