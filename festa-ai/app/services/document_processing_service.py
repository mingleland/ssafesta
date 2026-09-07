"""원본 다운로드→SHA-256 검증→파싱→청킹→Embedding 계산 (S15P21A604-184).

Spring에 결과를 전달하는 batch 전송·finalize·heartbeat·failed 호출은
``S15P21A604-124``의 책임이며 이 서비스는 관여하지 않는다. 이 서비스가 반환하는
``EmbeddedChunk`` 목록을 그대로 전달용 batch로 나누는 것이 124의 일이다.

FastAPI는 이 문서의 어떤 상태도 자체 DB에 두지 않는다 (S15P21A604-449) — 여기서
만든 결과는 호출자가 즉시 Spring에 전송하고 버린다.
"""

from __future__ import annotations

import asyncio
import hashlib
from collections.abc import Callable, Sequence
from dataclasses import dataclass

from app.api.schemas.documents import DocumentContentType, StorageProvider
from app.providers.document_parser import DocumentParser
from app.providers.embedding import EmbeddingProvider
from app.providers.storage import ObjectStorage
from app.services.text_chunker import TokenCodec, chunk_pages


@dataclass(frozen=True, slots=True)
class ProcessingSnapshot:
    """Spring이 검증해 전달한, 이번 attempt가 처리할 문서 원본 식별 정보다."""

    job_id: int
    attempt_no: int
    document_id: int
    booth_id: int
    agent_id: int
    original_filename: str
    content_type: DocumentContentType
    file_size_bytes: int
    storage_provider: StorageProvider
    storage_bucket: str
    object_key: str
    source_hash: str


@dataclass(frozen=True, slots=True)
class EmbeddedChunk:
    """Spring `ChunkPayload`로 그대로 옮겨 담을 수 있는 계산 결과다."""

    chunk_no: int
    content: str
    embedding: tuple[float, ...]
    embedding_model_id: str
    page_number: int | None
    section: str | None


class SourceHashMismatchError(Exception):
    """다운로드한 원본의 SHA-256이 Spring이 전달한 sourceHash와 다르다."""


class DocumentEmbeddingService:
    """한 문서 원본을 검증된 Embedding 결과 목록으로 바꾼다."""

    def __init__(
        self,
        *,
        storage_factory: Callable[[StorageProvider], ObjectStorage],
        parser: DocumentParser,
        embedding_provider: EmbeddingProvider,
        chunk_size: int,
        chunk_overlap: int,
        codec: TokenCodec,
        embedding_batch_size: int = 96,
    ) -> None:
        if embedding_batch_size <= 0:
            raise ValueError("embedding_batch_size는 1 이상이어야 합니다.")
        self._storage_factory = storage_factory
        self._parser = parser
        self._embedding_provider = embedding_provider
        self._chunk_size = chunk_size
        self._chunk_overlap = chunk_overlap
        self._codec = codec
        self._embedding_batch_size = embedding_batch_size

    async def compute_embedded_chunks(
        self, snapshot: ProcessingSnapshot
    ) -> tuple[EmbeddedChunk, ...]:
        storage = self._storage_factory(snapshot.storage_provider)
        content = await asyncio.to_thread(storage.get_object, snapshot.object_key)

        actual_hash = hashlib.sha256(content).hexdigest()
        if actual_hash != snapshot.source_hash:
            raise SourceHashMismatchError(
                f"expected sourceHash={snapshot.source_hash}, got {actual_hash}"
            )

        pages = await asyncio.to_thread(
            self._parser.parse, content, snapshot.content_type
        )
        chunks = await asyncio.to_thread(
            chunk_pages,
            pages,
            chunk_size=self._chunk_size,
            overlap=self._chunk_overlap,
            codec=self._codec,
        )

        embedded: list[EmbeddedChunk] = []
        for start in range(0, len(chunks), self._embedding_batch_size):
            group = chunks[start : start + self._embedding_batch_size]
            batch = await self._embedding_provider.embed(
                [chunk.content for chunk in group]
            )
            embedded.extend(
                EmbeddedChunk(
                    chunk_no=chunk.chunk_no,
                    content=chunk.content,
                    embedding=tuple(vector),
                    embedding_model_id=batch.model_id,
                    page_number=chunk.page_number,
                    section=chunk.section,
                )
                for chunk, vector in zip(group, batch.vectors, strict=True)
            )
        return tuple(embedded)
