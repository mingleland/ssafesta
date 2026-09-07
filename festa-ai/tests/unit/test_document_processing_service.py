"""Download→검증→파싱→청킹→Embedding 계산 파이프라인 테스트 (S15P21A604-184).

Spring 전달(청크 batch 전송·finalize)은 S15P21A604-124의 책임이라 여기서 검증하지
않는다 — 이 서비스는 순수하게 원본을 검증된 Embedding 결과로 바꾸는 계산만 한다.
"""

from __future__ import annotations

import hashlib

import pytest

from app.api.schemas.documents import DocumentContentType, StorageProvider
from app.providers.document_parser import DefaultDocumentParser
from app.providers.embedding import EmbeddingBatch
from app.services.document_processing_service import (
    DocumentEmbeddingService,
    ProcessingSnapshot,
    SourceHashMismatchError,
)
from tests.fakes.storage import FakeObjectStorage, StoredObject


class _WordCodec:
    """공백 기준 1단어 = 1토큰인 예측 가능한 가짜 codec (test_text_chunker.py와 동일 패턴)."""

    def encode(self, text: str) -> list[int]:
        return list(range(len(text.split())))

    def decode(self, tokens: list[int]) -> str:
        return " ".join(f"tok{token}" for token in tokens)


class _FakeEmbeddingProvider:
    model_id = "fake-embedding-v1"
    dimension = 3

    def __init__(self) -> None:
        self.batch_sizes: list[int] = []

    async def embed(self, texts):
        self.batch_sizes.append(len(texts))
        vectors = tuple(
            tuple(float(len(text) + index) for index in range(self.dimension))
            for text in texts
        )
        return EmbeddingBatch(model_id=self.model_id, vectors=vectors)


def _snapshot(*, object_key: str = "documents/1.txt", source_hash: str) -> ProcessingSnapshot:
    return ProcessingSnapshot(
        job_id=501,
        attempt_no=0,
        document_id=9001,
        booth_id=7,
        agent_id=3,
        original_filename="guide.txt",
        content_type=DocumentContentType.TEXT,
        file_size_bytes=100,
        storage_provider=StorageProvider.R2,
        storage_bucket="festa-docs",
        object_key=object_key,
        source_hash=source_hash,
    )


def _service(storage: FakeObjectStorage, embedding_provider, *, embedding_batch_size: int = 96):
    return DocumentEmbeddingService(
        storage_factory=lambda _provider: storage,
        parser=DefaultDocumentParser(),
        embedding_provider=embedding_provider,
        chunk_size=4,
        chunk_overlap=1,
        codec=_WordCodec(),
        embedding_batch_size=embedding_batch_size,
    )


@pytest.mark.asyncio
async def test_compute_embedded_chunks_downloads_parses_chunks_and_embeds() -> None:
    content = b"a b c d e f g h"
    storage = FakeObjectStorage({"documents/1.txt": StoredObject(body=content)})
    embedding_provider = _FakeEmbeddingProvider()
    service = _service(storage, embedding_provider)
    source_hash = hashlib.sha256(content).hexdigest()

    chunks = await service.compute_embedded_chunks(
        _snapshot(source_hash=source_hash)
    )

    assert [chunk.chunk_no for chunk in chunks] == [0, 1, 2]
    assert all(chunk.embedding_model_id == "fake-embedding-v1" for chunk in chunks)
    assert all(len(chunk.embedding) == 3 for chunk in chunks)
    assert all(chunk.page_number == 1 for chunk in chunks)


@pytest.mark.asyncio
async def test_compute_embedded_chunks_rejects_source_hash_mismatch() -> None:
    content = b"a b c d e f g h"
    storage = FakeObjectStorage({"documents/1.txt": StoredObject(body=content)})
    service = _service(storage, _FakeEmbeddingProvider())

    with pytest.raises(SourceHashMismatchError):
        await service.compute_embedded_chunks(_snapshot(source_hash="f" * 64))


@pytest.mark.asyncio
async def test_compute_embedded_chunks_calls_embedding_provider_in_configured_batches() -> None:
    content = b"a b c d e f g h i j k l"  # chunk_size=4, overlap=1 → 4 chunks
    storage = FakeObjectStorage({"documents/1.txt": StoredObject(body=content)})
    embedding_provider = _FakeEmbeddingProvider()
    service = _service(storage, embedding_provider, embedding_batch_size=2)
    source_hash = hashlib.sha256(content).hexdigest()

    chunks = await service.compute_embedded_chunks(_snapshot(source_hash=source_hash))

    assert len(chunks) == 4
    assert embedding_provider.batch_sizes == [2, 2]
