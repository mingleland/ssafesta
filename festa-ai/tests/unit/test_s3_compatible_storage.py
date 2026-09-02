"""boto3 기반 R2·MinIO storage adapter와 원본 metadata 검증 테스트 (T045)."""

from __future__ import annotations

import hashlib

import boto3
import pytest
from moto import mock_aws

from app.providers.s3_compatible_storage import S3CompatibleObjectStorage, verify_and_download
from app.providers.storage import (
    ObjectNotFoundError,
    ObjectStorageError,
    ObjectVerificationError,
)


@pytest.fixture
def bucket():
    with mock_aws():
        client = boto3.client("s3", region_name="us-east-1")
        client.create_bucket(Bucket="test-bucket")
        yield "test-bucket"


@pytest.fixture
def storage(bucket):
    return S3CompatibleObjectStorage(
        endpoint_url=None,
        bucket=bucket,
        access_key_id="test-key",
        secret_access_key="test-secret",
        region_name="us-east-1",
    )


def test_put_and_get_round_trip(storage) -> None:
    storage.put_object("documents/1.pdf", b"hello world", content_type="application/pdf")

    assert storage.get_object("documents/1.pdf") == b"hello world"


def test_head_object_returns_size_and_content_type(storage) -> None:
    storage.put_object("documents/1.pdf", b"hello world", content_type="application/pdf")

    metadata = storage.head_object("documents/1.pdf")

    assert metadata.content_length == len(b"hello world")
    assert metadata.content_type == "application/pdf"


def test_head_object_raises_object_not_found_for_missing_key(storage) -> None:
    with pytest.raises(ObjectNotFoundError):
        storage.head_object("does/not/exist.pdf")


def test_get_object_raises_object_not_found_for_missing_key(storage) -> None:
    with pytest.raises(ObjectNotFoundError):
        storage.get_object("does/not/exist.pdf")


def test_get_object_against_missing_bucket_raises_storage_error_without_leaking_detail() -> None:
    with mock_aws():
        storage = S3CompatibleObjectStorage(
            endpoint_url=None,
            bucket="no-such-bucket",
            access_key_id="test-key",
            secret_access_key="test-secret",
            region_name="us-east-1",
        )

        with pytest.raises(ObjectStorageError) as exc_info:
            storage.get_object("documents/1.pdf")

        assert "test-secret" not in str(exc_info.value)


def test_verify_and_download_returns_body_when_size_and_sha256_match(storage) -> None:
    body = b"hello world"
    storage.put_object("documents/1.pdf", body, content_type="application/pdf")
    expected_sha256 = hashlib.sha256(body).hexdigest()

    result = verify_and_download(
        storage,
        "documents/1.pdf",
        expected_size=len(body),
        expected_sha256=expected_sha256,
    )

    assert result == body


def test_verify_and_download_raises_on_size_mismatch(storage) -> None:
    body = b"hello world"
    storage.put_object("documents/1.pdf", body, content_type="application/pdf")

    with pytest.raises(ObjectVerificationError):
        verify_and_download(
            storage,
            "documents/1.pdf",
            expected_size=len(body) + 1,
            expected_sha256=hashlib.sha256(body).hexdigest(),
        )


def test_verify_and_download_raises_on_sha256_mismatch(storage) -> None:
    body = b"hello world"
    storage.put_object("documents/1.pdf", body, content_type="application/pdf")

    with pytest.raises(ObjectVerificationError):
        verify_and_download(
            storage,
            "documents/1.pdf",
            expected_size=len(body),
            expected_sha256="0" * 64,
        )


def test_verify_and_download_propagates_not_found(storage) -> None:
    with pytest.raises(ObjectNotFoundError):
        verify_and_download(
            storage,
            "does/not/exist.pdf",
            expected_size=1,
            expected_sha256="0" * 64,
        )
