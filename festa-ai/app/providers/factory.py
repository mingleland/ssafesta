"""검증된 설정값으로 현재 환경의 Embedding Provider 구현을 선택한다."""

from __future__ import annotations

from typing import TYPE_CHECKING

import httpx

from app.providers.embedding import EmbeddingProvider
from app.providers.managed_embedding import ManagedEmbeddingProvider
from app.providers.mock import MockEmbeddingProvider

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
