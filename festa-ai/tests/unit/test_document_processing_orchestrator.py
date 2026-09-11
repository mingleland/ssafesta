"""전체 흐름 오케스트레이션 테스트 (S15P21A604-124/149): 계산 → batch 전송 → finalize/failed.

heartbeat는 별도 tick 주입 없이 "시작되고 완료 후 취소된다"만 확인한다 — 주기
자체는 `SpringDocumentResultClient.heartbeat` 위임이라 그 client 테스트의 몫이다.

S15P21A604-149: 처리 시작 직전·finalize 직전 두 번 Booth Lease를 확인해, 만료된
Booth의 문서가 READY로 전환되는 것을 막는다 (spec 007 FR-015).
"""

from __future__ import annotations

import asyncio
import logging

import pytest

from app.clients.spring_booth_access import BoothAccessResult
from app.clients.spring_document_result import (
    SpringDocumentResultJobGone,
    SpringDocumentResultStaleAttempt,
    SpringDocumentResultUnavailable,
)
from app.services.document_processing_orchestrator import DocumentProcessingOrchestrator
from app.services.document_processing_service import EmbeddedChunk

_ALLOWED = BoothAccessResult(allowed=True, lease_ends_at="2026-09-07T12:00:00Z", denial_code=None)
_EXPIRED = BoothAccessResult(allowed=False, lease_ends_at=None, denial_code="BOOTH_LEASE_EXPIRED")


class _FakeBoothAccessClient:
    def __init__(self, results: list[BoothAccessResult] | None = None) -> None:
        self._results = list(results) if results is not None else None
        self.calls: list[tuple[int, int]] = []

    async def check(self, *, booth_id: int, agent_id: int) -> BoothAccessResult:
        self.calls.append((booth_id, agent_id))
        if self._results is not None:
            return self._results.pop(0)
        return _ALLOWED


def _chunk(chunk_no: int) -> EmbeddedChunk:
    return EmbeddedChunk(
        chunk_no=chunk_no,
        content=f"chunk-{chunk_no}",
        embedding=(0.1, 0.2, 0.3),
        embedding_model_id="fake-embedding-v1",
        page_number=1,
        section=None,
    )


class _FakeEmbeddingService:
    def __init__(self, chunks=None, error=None) -> None:
        self._chunks = chunks or ()
        self._error = error

    async def compute_embedded_chunks(self, snapshot):
        if self._error is not None:
            raise self._error
        return self._chunks


class _FakeResultClient:
    def __init__(self, *, batch_error=None, failed_error=None) -> None:
        self.chunk_batches: list[tuple[int, list]] = []
        self.finalize_calls: list[dict] = []
        self.heartbeats: list[tuple[int, int]] = []
        self.failed_calls: list[dict] = []
        self._batch_error = batch_error
        self._failed_error = failed_error

    async def chunk_batch(self, *, job_id, attempt_no, batch_seq, chunks):
        if self._batch_error is not None:
            raise self._batch_error
        self.chunk_batches.append((batch_seq, list(chunks)))

    async def finalize(self, **kwargs):
        self.finalize_calls.append(kwargs)

    async def heartbeat(self, *, job_id, attempt_no):
        self.heartbeats.append((job_id, attempt_no))

    async def failed(self, **kwargs):
        if self._failed_error is not None:
            raise self._failed_error
        self.failed_calls.append(kwargs)


def _snapshot():
    from app.api.schemas.documents import DocumentContentType, StorageProvider
    from app.services.document_processing_service import ProcessingSnapshot

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
        object_key="documents/1.txt",
        source_hash="a" * 64,
    )


@pytest.mark.asyncio
async def test_run_sends_one_batch_and_finalizes_on_success() -> None:
    chunks = tuple(_chunk(i) for i in range(3))
    embedding_service = _FakeEmbeddingService(chunks=chunks)
    result_client = _FakeResultClient()
    orchestrator = DocumentProcessingOrchestrator(
        embedding_service=embedding_service,
        result_client=result_client,
        booth_access_client=_FakeBoothAccessClient(),
        heartbeat_interval_seconds=60.0,
        max_chunks_per_batch=200,
    )

    await orchestrator.run(_snapshot())

    assert len(result_client.chunk_batches) == 1
    batch_seq, sent_chunks = result_client.chunk_batches[0]
    assert batch_seq == 0
    assert [c.chunk_no for c in sent_chunks] == [0, 1, 2]
    assert result_client.finalize_calls == [
        {
            "job_id": 501,
            "attempt_no": 0,
            "source_hash": "a" * 64,
            "total_chunk_count": 3,
            "embedding_model_id": "fake-embedding-v1",
        }
    ]
    assert result_client.failed_calls == []


@pytest.mark.asyncio
async def test_run_splits_into_multiple_batches_by_max_chunks() -> None:
    chunks = tuple(_chunk(i) for i in range(5))
    orchestrator = DocumentProcessingOrchestrator(
        embedding_service=_FakeEmbeddingService(chunks=chunks),
        result_client=(result_client := _FakeResultClient()),
        booth_access_client=_FakeBoothAccessClient(),
        heartbeat_interval_seconds=60.0,
        max_chunks_per_batch=2,
    )

    await orchestrator.run(_snapshot())

    assert [seq for seq, _ in result_client.chunk_batches] == [0, 1, 2]
    assert [len(chunks_) for _, chunks_ in result_client.chunk_batches] == [2, 2, 1]


@pytest.mark.asyncio
async def test_run_reports_failed_when_embedding_computation_raises() -> None:
    result_client = _FakeResultClient()
    orchestrator = DocumentProcessingOrchestrator(
        embedding_service=_FakeEmbeddingService(error=ValueError("빈 문서")),
        result_client=result_client,
        booth_access_client=_FakeBoothAccessClient(),
        heartbeat_interval_seconds=60.0,
    )

    await orchestrator.run(_snapshot())

    assert result_client.chunk_batches == []
    assert result_client.failed_calls == [
        {
            "job_id": 501,
            "attempt_no": 0,
            "failure_code": "CHUNKING_FAILED",
            "retryable": False,
        }
    ]


@pytest.mark.asyncio
async def test_run_logs_safe_fields_when_failure_callback_is_undelivered(caplog) -> None:
    raw_error = "provider 원문 오류입니다"
    result_client = _FakeResultClient(
        failed_error=SpringDocumentResultUnavailable(raw_error)
    )
    orchestrator = DocumentProcessingOrchestrator(
        embedding_service=_FakeEmbeddingService(error=ValueError(raw_error)),
        result_client=result_client,
        booth_access_client=_FakeBoothAccessClient(),
        heartbeat_interval_seconds=60.0,
    )

    with caplog.at_level(
        logging.WARNING, logger="app.services.document_processing_orchestrator"
    ):
        await orchestrator.run(_snapshot())

    record = caplog.records[-1]
    assert record.getMessage() == "spring_failure_callback_undelivered"
    assert record.job_id == 501
    assert record.document_id == 9001
    assert record.status == "UNDELIVERED"
    assert raw_error not in caplog.text


@pytest.mark.asyncio
async def test_run_does_not_report_failed_when_batch_send_hits_stale_attempt() -> None:
    result_client = _FakeResultClient(
        batch_error=SpringDocumentResultStaleAttempt("stale")
    )
    orchestrator = DocumentProcessingOrchestrator(
        embedding_service=_FakeEmbeddingService(chunks=(_chunk(0),)),
        result_client=result_client,
        booth_access_client=_FakeBoothAccessClient(),
        heartbeat_interval_seconds=60.0,
    )

    await orchestrator.run(_snapshot())

    assert result_client.failed_calls == []


@pytest.mark.asyncio
async def test_run_does_not_report_failed_when_batch_send_hits_job_gone() -> None:
    result_client = _FakeResultClient(batch_error=SpringDocumentResultJobGone("gone"))
    orchestrator = DocumentProcessingOrchestrator(
        embedding_service=_FakeEmbeddingService(chunks=(_chunk(0),)),
        result_client=result_client,
        booth_access_client=_FakeBoothAccessClient(),
        heartbeat_interval_seconds=60.0,
    )

    await orchestrator.run(_snapshot())

    assert result_client.failed_calls == []


@pytest.mark.asyncio
async def test_run_sends_heartbeat_while_processing_and_cancels_after() -> None:
    class _SlowEmbeddingService(_FakeEmbeddingService):
        async def compute_embedded_chunks(self, snapshot):
            await asyncio.sleep(0.05)
            return (_chunk(0),)

    result_client = _FakeResultClient()
    orchestrator = DocumentProcessingOrchestrator(
        embedding_service=_SlowEmbeddingService(),
        result_client=result_client,
        booth_access_client=_FakeBoothAccessClient(),
        heartbeat_interval_seconds=0.01,
    )

    await orchestrator.run(_snapshot())

    assert len(result_client.heartbeats) >= 1
    assert result_client.heartbeats[0] == (501, 0)


@pytest.mark.asyncio
async def test_run_skips_processing_when_lease_already_expired_at_start() -> None:
    booth_access = _FakeBoothAccessClient(results=[_EXPIRED])
    embedding_service = _FakeEmbeddingService(chunks=(_chunk(0),))
    result_client = _FakeResultClient()
    orchestrator = DocumentProcessingOrchestrator(
        embedding_service=embedding_service,
        result_client=result_client,
        booth_access_client=booth_access,
        heartbeat_interval_seconds=60.0,
    )

    await orchestrator.run(_snapshot())

    assert booth_access.calls == [(7, 3)]
    assert result_client.chunk_batches == []
    assert result_client.finalize_calls == []
    assert result_client.failed_calls == [
        {
            "job_id": 501,
            "attempt_no": 0,
            "failure_code": "BOOTH_LEASE_EXPIRED",
            "retryable": False,
        }
    ]


@pytest.mark.asyncio
async def test_run_skips_finalize_when_lease_expires_after_processing_starts() -> None:
    booth_access = _FakeBoothAccessClient(results=[_ALLOWED, _EXPIRED])
    embedding_service = _FakeEmbeddingService(chunks=(_chunk(0), _chunk(1)))
    result_client = _FakeResultClient()
    orchestrator = DocumentProcessingOrchestrator(
        embedding_service=embedding_service,
        result_client=result_client,
        booth_access_client=booth_access,
        heartbeat_interval_seconds=60.0,
    )

    await orchestrator.run(_snapshot())

    assert len(booth_access.calls) == 2
    assert len(result_client.chunk_batches) == 1
    assert result_client.finalize_calls == []
    assert result_client.failed_calls == [
        {
            "job_id": 501,
            "attempt_no": 0,
            "failure_code": "BOOTH_LEASE_EXPIRED",
            "retryable": False,
        }
    ]
