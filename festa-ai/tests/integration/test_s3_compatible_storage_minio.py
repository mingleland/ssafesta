"""실제 로컬 MinIO를 대상으로 한 S3-compatible storage adapter 통합 테스트 (T045).

옵트인 테스트다: `TEST_MINIO_ENDPOINT`가 가리키는 처분 가능한 로컬 MinIO
인스턴스가 있을 때만 실행한다 (spec 007 S15P21A604-120 완료 조건).
"""

from __future__ import annotations

import hashlib
import os
import uuid

import boto3
import pytest
from botocore.exceptions import ClientError

from app.providers.s3_compatible_storage import S3CompatibleObjectStorage, verify_and_download
from app.providers.storage import ObjectNotFoundError, ObjectVerificationError

MINIO_ENDPOINT = os.getenv("TEST_MINIO_ENDPOINT")
MINIO_ACCESS_KEY = os.getenv("TEST_MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.getenv("TEST_MINIO_SECRET_KEY", "minioadmin")
MINIO_BUCKET = os.getenv("TEST_MINIO_BUCKET", "festa-ai-storage-it")

pytestmark = pytest.mark.skipif(
    not MINIO_ENDPOINT,
    reason="TEST_MINIO_ENDPOINT not set — skipping tests that need a real local MinIO instance",
)


@pytest.fixture(scope="module", autouse=True)
def _ensure_bucket():
    client = boto3.client(
        "s3",
        endpoint_url=MINIO_ENDPOINT,
        aws_access_key_id=MINIO_ACCESS_KEY,
        aws_secret_access_key=MINIO_SECRET_KEY,
        region_name="us-east-1",
    )
    try:
        client.head_bucket(Bucket=MINIO_BUCKET)
    except ClientError:
        client.create_bucket(Bucket=MINIO_BUCKET)


@pytest.fixture
def storage() -> S3CompatibleObjectStorage:
    return S3CompatibleObjectStorage(
        endpoint_url=MINIO_ENDPOINT,
        bucket=MINIO_BUCKET,
        access_key_id=MINIO_ACCESS_KEY,
        secret_access_key=MINIO_SECRET_KEY,
        region_name="us-east-1",
    )


@pytest.fixture
def object_key() -> str:
    return f"it/{uuid.uuid4()}.pdf"


def test_put_and_get_round_trip_against_real_minio(storage, object_key) -> None:
    body = b"%PDF-1.4 real minio round trip"

    storage.put_object(object_key, body, content_type="application/pdf")

    assert storage.get_object(object_key) == body


def test_head_object_reports_size_and_content_type_from_real_minio(storage, object_key) -> None:
    body = b"content-type and size check"
    storage.put_object(object_key, body, content_type="application/pdf")

    metadata = storage.head_object(object_key)

    assert metadata.content_length == len(body)
    assert metadata.content_type == "application/pdf"


def test_get_object_raises_not_found_for_real_minio_missing_key(storage, object_key) -> None:
    with pytest.raises(ObjectNotFoundError):
        storage.get_object(object_key)


def test_verify_and_download_rejects_metadata_mismatch_against_real_minio(
    storage, object_key
) -> None:
    body = b"tampered size check"
    storage.put_object(object_key, body, content_type="application/pdf")

    with pytest.raises(ObjectVerificationError):
        verify_and_download(
            storage,
            object_key,
            expected_size=len(body) + 1,
            expected_sha256=hashlib.sha256(body).hexdigest(),
        )


def test_verify_and_download_succeeds_against_real_minio(storage, object_key) -> None:
    body = b"end to end verified download"
    storage.put_object(object_key, body, content_type="application/pdf")

    result = verify_and_download(
        storage,
        object_key,
        expected_size=len(body),
        expected_sha256=hashlib.sha256(body).hexdigest(),
    )

    assert result == body
