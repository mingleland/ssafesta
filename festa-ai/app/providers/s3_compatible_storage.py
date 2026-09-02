"""R2·MinIO 등 S3-compatible object storage adapter와 원본 metadata 검증 (T045)."""

from __future__ import annotations

import hashlib

import boto3
from botocore.exceptions import ClientError
from pydantic import SecretStr

from app.providers.storage import (
    ObjectMetadata,
    ObjectNotFoundError,
    ObjectStorageError,
    ObjectVerificationError,
)

_NOT_FOUND_ERROR_CODES = {"404", "NoSuchKey", "NotFound"}


class S3CompatibleObjectStorage:
    """boto3 S3 client를 감싸 R2·MinIO를 endpoint 설정만으로 전환 가능하게 한다."""

    def __init__(
        self,
        *,
        endpoint_url: str | None,
        bucket: str,
        access_key_id: str | SecretStr,
        secret_access_key: str | SecretStr,
        region_name: str = "auto",
    ) -> None:
        self._bucket = bucket
        self._client = boto3.client(
            "s3",
            endpoint_url=endpoint_url,
            aws_access_key_id=_secret_value(access_key_id),
            aws_secret_access_key=_secret_value(secret_access_key),
            region_name=region_name,
        )

    def put_object(
        self, object_key: str, body: bytes, *, content_type: str | None = None
    ) -> None:
        kwargs = {"Bucket": self._bucket, "Key": object_key, "Body": body}
        if content_type is not None:
            kwargs["ContentType"] = content_type
        try:
            self._client.put_object(**kwargs)
        except ClientError as exc:
            raise _wrap_client_error(exc) from exc

    def head_object(self, object_key: str) -> ObjectMetadata:
        try:
            response = self._client.head_object(Bucket=self._bucket, Key=object_key)
        except ClientError as exc:
            raise _wrap_client_error(exc) from exc
        return ObjectMetadata(
            content_length=response["ContentLength"],
            content_type=response.get("ContentType"),
        )

    def get_object(self, object_key: str) -> bytes:
        try:
            response = self._client.get_object(Bucket=self._bucket, Key=object_key)
            return response["Body"].read()
        except ClientError as exc:
            raise _wrap_client_error(exc) from exc


def verify_and_download(
    storage: S3CompatibleObjectStorage,
    object_key: str,
    *,
    expected_size: int,
    expected_sha256: str,
) -> bytes:
    """다운로드 전 크기를, 다운로드 후 SHA-256을 검증한다 (SOURCE_HASH_MISMATCH)."""
    metadata = storage.head_object(object_key)
    if metadata.content_length != expected_size:
        raise ObjectVerificationError(
            f"크기 불일치: expected={expected_size}, actual={metadata.content_length}"
        )

    body = storage.get_object(object_key)
    actual_sha256 = hashlib.sha256(body).hexdigest()
    if actual_sha256 != expected_sha256:
        raise ObjectVerificationError("SHA-256 불일치")

    return body


def _secret_value(value: str | SecretStr) -> str:
    return value.get_secret_value() if isinstance(value, SecretStr) else value


def _wrap_client_error(exc: ClientError) -> ObjectStorageError:
    error_code = exc.response.get("Error", {}).get("Code", "")
    if error_code in _NOT_FOUND_ERROR_CODES:
        return ObjectNotFoundError("SOURCE_NOT_FOUND")
    return ObjectStorageError("STORAGE_PROVIDER_ERROR")
