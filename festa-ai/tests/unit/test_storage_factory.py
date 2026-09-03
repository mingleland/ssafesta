"""설정에서 Job의 storage_provider(R2/MINIO_LOCAL)로 adapter를 선택하는 factory 테스트."""

from __future__ import annotations

from types import SimpleNamespace

import pytest
from pydantic import SecretStr

from app.api.schemas.documents import StorageProvider
from app.providers.factory import create_object_storage
from app.providers.s3_compatible_storage import S3CompatibleObjectStorage


def _settings() -> SimpleNamespace:
    return SimpleNamespace(
        r2_endpoint="https://r2.example.test",
        r2_bucket="festa-r2-bucket",
        r2_access_key_id=SecretStr("r2-key"),
        r2_secret_access_key=SecretStr("r2-secret"),
        r2_region="auto",
        minio_endpoint="http://minio.local:9000",
        minio_bucket="festa-minio-bucket",
        minio_access_key_id=SecretStr("minio-key"),
        minio_secret_access_key=SecretStr("minio-secret"),
    )


def test_create_object_storage_selects_r2_endpoint_and_bucket() -> None:
    storage = create_object_storage(_settings(), StorageProvider.R2)  # type: ignore[arg-type]

    assert isinstance(storage, S3CompatibleObjectStorage)
    assert storage._bucket == "festa-r2-bucket"
    assert storage._client.meta.endpoint_url == "https://r2.example.test"


def test_create_object_storage_selects_minio_endpoint_and_bucket() -> None:
    storage = create_object_storage(_settings(), StorageProvider.MINIO_LOCAL)  # type: ignore[arg-type]

    assert isinstance(storage, S3CompatibleObjectStorage)
    assert storage._bucket == "festa-minio-bucket"
    assert storage._client.meta.endpoint_url == "http://minio.local:9000"


def test_create_object_storage_rejects_unknown_provider() -> None:
    with pytest.raises(ValueError):
        create_object_storage(_settings(), "UNKNOWN")  # type: ignore[arg-type]
