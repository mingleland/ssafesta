"""Provide the single scope-safe entry point for RAG vector retrieval."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Sequence

from sqlalchemy import Select, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import EMBEDDING_DIMENSION, DocumentChunk
from app.providers.embedding import EmbeddingVector


@dataclass(frozen=True, slots=True)
class ChunkScope:
    """Server-owned immutable Booth and Agent search boundary."""

    booth_id: int
    agent_id: int

    def __post_init__(self) -> None:
        if self.booth_id <= 0 or self.agent_id <= 0:
            raise ValueError("booth_id and agent_id must be positive")


@dataclass(frozen=True, slots=True)
class RetrievedChunk:
    """Scope-bearing retrieval result revalidated before leaving the repository."""

    chunk_id: int
    document_id: int
    booth_id: int
    agent_id: int
    chunk_no: int
    content: str
    embedding_model_id: str
    page_number: int | None
    section: str | None
    distance: float


class ChunkRepository:
    """Search only READY-projected chunks inside one immutable server scope."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def search_ready_chunks(
        self,
        *,
        scope: ChunkScope,
        query_embedding: Sequence[float],
        top_k: int,
    ) -> tuple[RetrievedChunk, ...]:
        embedding = self._validate_search_input(query_embedding, top_k=top_k)
        statement = self._build_search_statement(
            scope=scope,
            query_embedding=embedding,
            top_k=top_k,
        )
        rows = (await self._session.execute(statement)).all()

        chunks = tuple(
            RetrievedChunk(
                chunk_id=row.id,
                document_id=row.document_id,
                booth_id=row.booth_id,
                agent_id=row.agent_id,
                chunk_no=row.chunk_no,
                content=row.content,
                embedding_model_id=row.embedding_model_id,
                page_number=row.page_number,
                section=row.section,
                distance=float(row.distance),
            )
            for row in rows
        )
        if any(
            chunk.booth_id != scope.booth_id or chunk.agent_id != scope.agent_id
            for chunk in chunks
        ):
            raise RuntimeError("RAG_SCOPE_MISMATCH")
        return chunks

    @staticmethod
    def _validate_search_input(
        query_embedding: Sequence[float], *, top_k: int
    ) -> EmbeddingVector:
        if isinstance(top_k, bool) or not isinstance(top_k, int) or top_k <= 0:
            raise ValueError("top_k must be a positive integer")
        if len(query_embedding) != EMBEDDING_DIMENSION:
            raise ValueError(
                f"query embedding must have {EMBEDDING_DIMENSION} dimensions"
            )
        if any(
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(float(value))
            for value in query_embedding
        ):
            raise ValueError("query embedding must contain finite numbers")
        normalized = tuple(float(value) for value in query_embedding)
        if not any(value != 0.0 for value in normalized):
            raise ValueError("query embedding must have a non-zero norm")
        return normalized

    @staticmethod
    def _build_search_statement(
        *,
        scope: ChunkScope,
        query_embedding: EmbeddingVector,
        top_k: int,
    ) -> Select[tuple]:
        # pgvector's SQLAlchemy bind processor accepts list/ndarray while the
        # provider contract intentionally exposes immutable tuple vectors.
        distance = DocumentChunk.embedding.cosine_distance(list(query_embedding))
        return (
            select(
                DocumentChunk.id,
                DocumentChunk.document_id,
                DocumentChunk.booth_id,
                DocumentChunk.agent_id,
                DocumentChunk.chunk_no,
                DocumentChunk.content,
                DocumentChunk.embedding_model_id,
                DocumentChunk.page_number,
                DocumentChunk.section,
                distance.label("distance"),
            )
            .where(
                DocumentChunk.booth_id == scope.booth_id,
                DocumentChunk.agent_id == scope.agent_id,
                DocumentChunk.searchable.is_(True),
            )
            .order_by(distance.asc(), DocumentChunk.id.asc())
            .limit(top_k)
        )
