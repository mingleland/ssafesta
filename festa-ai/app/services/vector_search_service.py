"""Connect question embedding to the scope-safe RAG chunk repository."""

from __future__ import annotations

from app.db.models import EMBEDDING_DIMENSION
from app.providers.embedding import EmbeddingProvider
from app.repositories.chunk_repository import (
    ChunkRepository,
    ChunkScope,
    RetrievedChunk,
)


class VectorSearchService:
    """Embed one question and retrieve only chunks allowed by its server scope."""

    def __init__(
        self,
        *,
        embedding_provider: EmbeddingProvider,
        chunk_repository: ChunkRepository,
    ) -> None:
        self._embedding_provider = embedding_provider
        self._chunk_repository = chunk_repository

    async def search(
        self,
        *,
        question: str,
        scope: ChunkScope,
        top_k: int,
    ) -> tuple[RetrievedChunk, ...]:
        if not question.strip():
            raise ValueError("question must not be blank")

        batch = await self._embedding_provider.embed((question,))
        if (
            len(batch.vectors) != 1
            or self._embedding_provider.dimension != EMBEDDING_DIMENSION
            or len(batch.vectors[0]) != EMBEDDING_DIMENSION
        ):
            raise RuntimeError("EMBEDDING_DIMENSION_MISMATCH")

        return await self._chunk_repository.search_ready_chunks(
            scope=scope,
            query_embedding=batch.vectors[0],
            top_k=top_k,
        )
