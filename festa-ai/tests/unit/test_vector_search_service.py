"""Verify question embedding is connected to the scoped vector repository."""

from __future__ import annotations

import pytest

from app.providers.embedding import EmbeddingBatch
from app.repositories.chunk_repository import ChunkScope
from app.services.vector_search_service import VectorSearchService


class _EmbeddingProvider:
    model_id = "embedding-v1"
    dimension = 1536

    def __init__(self) -> None:
        self.inputs: tuple[str, ...] | None = None

    async def embed(self, texts):
        self.inputs = tuple(texts)
        return EmbeddingBatch(
            model_id=self.model_id,
            vectors=((0.25,) * self.dimension,),
        )


class _Repository:
    def __init__(self) -> None:
        self.scope = None
        self.embedding = None
        self.top_k = None

    async def search_ready_chunks(self, *, scope, query_embedding, top_k):
        self.scope = scope
        self.embedding = query_embedding
        self.top_k = top_k
        return ()


@pytest.mark.asyncio
async def test_service_embeds_question_and_uses_server_scope() -> None:
    provider = _EmbeddingProvider()
    repository = _Repository()
    service = VectorSearchService(
        embedding_provider=provider,
        chunk_repository=repository,  # type: ignore[arg-type]
    )
    scope = ChunkScope(booth_id=10, agent_id=20)

    result = await service.search(question="운영 시간이 언제인가요?", scope=scope, top_k=3)

    assert result == ()
    assert provider.inputs == ("운영 시간이 언제인가요?",)
    assert repository.scope == scope
    assert repository.embedding == (0.25,) * 1536
    assert repository.top_k == 3


@pytest.mark.asyncio
async def test_blank_question_does_not_call_embedding_or_database() -> None:
    provider = _EmbeddingProvider()
    repository = _Repository()
    service = VectorSearchService(
        embedding_provider=provider,
        chunk_repository=repository,  # type: ignore[arg-type]
    )

    with pytest.raises(ValueError, match="question must not be blank"):
        await service.search(
            question="  ",
            scope=ChunkScope(booth_id=10, agent_id=20),
            top_k=3,
        )

    assert provider.inputs is None
    assert repository.scope is None


@pytest.mark.asyncio
async def test_invalid_embedding_batch_fails_before_database_search() -> None:
    provider = _EmbeddingProvider()
    repository = _Repository()
    service = VectorSearchService(
        embedding_provider=provider,
        chunk_repository=repository,  # type: ignore[arg-type]
    )
    provider.dimension = 1535

    with pytest.raises(RuntimeError, match="EMBEDDING_DIMENSION_MISMATCH"):
        await service.search(
            question="질문",
            scope=ChunkScope(booth_id=10, agent_id=20),
            top_k=3,
        )

    assert repository.scope is None
