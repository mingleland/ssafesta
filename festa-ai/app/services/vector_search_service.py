"""Connect question embedding to Spring's scope-safe chunk-search endpoint.

S15P21A604-449 이후: 검색은 로컬 pgvector가 아니라 Spring이 소유한다
(``S15P21A604-140`` 범위로 흡수된 T049). 이 서비스는 질의 Embedding만 계산하고
검색 자체는 ``SpringChunkSearchClient``에 맡긴다.
"""

from __future__ import annotations

from app.clients.spring_chunk_search import ChunkScope, RetrievedChunk, SpringChunkSearchClient
from app.providers.embedding import EMBEDDING_DIMENSION, EmbeddingProvider


class VectorSearchService:
    """Embed one question and delegate scope-safe retrieval to Spring."""

    def __init__(
        self,
        *,
        embedding_provider: EmbeddingProvider,
        chunk_search_client: SpringChunkSearchClient,
    ) -> None:
        self._embedding_provider = embedding_provider
        self._chunk_search_client = chunk_search_client

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

        return await self._chunk_search_client.search(
            scope=scope,
            query_embedding=batch.vectors[0],
            top_k=top_k,
        )
