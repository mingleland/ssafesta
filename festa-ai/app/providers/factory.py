"""검증된 설정값으로 현재 환경의 Embedding Provider 구현을 선택한다."""

from __future__ import annotations

from typing import TYPE_CHECKING

import httpx

from app.api.schemas.documents import StorageProvider
from app.providers.embedding import EmbeddingProvider
from app.providers.managed_embedding import ManagedEmbeddingProvider
from app.providers.mock import MockEmbeddingProvider
from app.providers.s3_compatible_storage import S3CompatibleObjectStorage
from app.providers.storage import ObjectStorage

if TYPE_CHECKING:
    from app.core.config import Settings


def create_embedding_provider(
    settings: Settings,
    *,
    client: httpx.AsyncClient | None = None,
) -> EmbeddingProvider:
    """`mock` 또는 `gms` 설정을 대응 구현체로 변환한다."""
    if settings.embedding_provider == "mock":
        return MockEmbeddingProvider()

    if (
        settings.embedding_model_id is None
        or settings.embedding_api_base_url is None
        or settings.embedding_api_key is None
    ):
        raise ValueError("GMS embedding provider settings are incomplete")

    return ManagedEmbeddingProvider(
        api_base_url=settings.embedding_api_base_url,
        api_path=settings.embedding_api_path,
        api_key=settings.embedding_api_key,
        model_id=settings.embedding_model_id,
        client=client,
    )


def create_object_storage(settings: Settings, provider: StorageProvider) -> ObjectStorage:
    """Job에 기록된 `storage_provider`로 다운로드용 adapter를 고른다.

    FastAPI는 활성 쓰기 Provider를 선택하지 않는다 (plan.md §2-2) — 매 요청마다
    문서 행의 storage_provider로 R2 또는 MinIO 중 하나를 고른다.
    """
    if provider == StorageProvider.R2:
        return S3CompatibleObjectStorage(
            endpoint_url=settings.r2_endpoint,
            bucket=settings.r2_bucket,
            access_key_id=settings.r2_access_key_id,
            secret_access_key=settings.r2_secret_access_key,
            region_name=settings.r2_region,
        )
    if provider == StorageProvider.MINIO_LOCAL:
        return S3CompatibleObjectStorage(
            endpoint_url=settings.minio_endpoint,
            bucket=settings.minio_bucket,
            access_key_id=settings.minio_access_key_id,
            secret_access_key=settings.minio_secret_access_key,
            # MinIO는 리전 개념이 없지만 boto3는 값을 요구한다.
            region_name="us-east-1",
        )
    raise ValueError(f"Unknown storage provider: {provider!r}")
