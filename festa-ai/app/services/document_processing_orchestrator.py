"""계산(184) 결과를 Spring에 전달한다: batch 전송 → finalize, 실패는 failed로 보고 (124).

heartbeat는 처리 중 background task로 30초마다 보내 lease를 연장한다
(document-result-api.yaml). `409`/`410`은 이 attempt가 이미 무의미하다는
뜻이라 — lease를 잃었거나 Job이 끝났다 — `failed`를 다시 부르지 않고 조용히
멈춘다. 그 밖의 실패만 `failure_policy.classify_failure`로 code/retryable을
정해 Spring에 보고한다.

S15P21A604-149: 처리 시작 직전과 finalize(공개) 직전, 두 번 Booth Lease를
확인한다 — Job attemptNo/JOB_GONE 판정은 "이 attempt가 유효한가"만 보므로
Job은 아직 살아있지만 Booth 임대가 그 사이 만료된 경우를 잡지 못한다.
만료가 확인되면 finalize를 호출하지 않고(FR-015: 만료 Booth 문서는 READY로
전환되면 안 된다) `BOOTH_LEASE_EXPIRED`로 `failed`를 보고한다.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from collections.abc import Sequence

from app.clients.spring_booth_access import SpringBoothAccessClient
from app.clients.spring_document_result import (
    ChunkBatchItem,
    SpringDocumentResultClient,
    SpringDocumentResultJobGone,
    SpringDocumentResultStaleAttempt,
    SpringDocumentResultUnavailable,
)
from app.services.document_processing_service import (
    BoothLeaseExpiredError,
    DocumentEmbeddingService,
    EmbeddedChunk,
    ProcessingSnapshot,
)
from app.services.failure_policy import classify_failure

logger = logging.getLogger(__name__)

DEFAULT_MAX_CHUNKS_PER_BATCH = 200
DEFAULT_MAX_BATCH_BYTES = 8 * 1024 * 1024

_ATTEMPT_LOST_ERRORS = (SpringDocumentResultStaleAttempt, SpringDocumentResultJobGone)


class DocumentProcessingOrchestrator:
    def __init__(
        self,
        *,
        embedding_service: DocumentEmbeddingService,
        result_client: SpringDocumentResultClient,
        booth_access_client: SpringBoothAccessClient,
        heartbeat_interval_seconds: float,
        max_chunks_per_batch: int = DEFAULT_MAX_CHUNKS_PER_BATCH,
        max_batch_bytes: int = DEFAULT_MAX_BATCH_BYTES,
    ) -> None:
        self._embedding_service = embedding_service
        self._result_client = result_client
        self._booth_access_client = booth_access_client
        self._heartbeat_interval_seconds = heartbeat_interval_seconds
        self._max_chunks_per_batch = max_chunks_per_batch
        self._max_batch_bytes = max_batch_bytes

    async def run(self, snapshot: ProcessingSnapshot) -> None:
        heartbeat_task = asyncio.create_task(self._heartbeat_loop(snapshot))
        try:
            await self._ensure_lease_active(snapshot)
            chunks = await self._embedding_service.compute_embedded_chunks(snapshot)
            await self._send_and_finalize(snapshot, chunks)
        except _ATTEMPT_LOST_ERRORS:
            logger.info(
                "document processing attempt superseded or job gone job_id=%s attempt_no=%s",
                snapshot.job_id,
                snapshot.attempt_no,
            )
        except Exception as exc:  # noqa: BLE001 — 모든 처리 실패를 Spring에 보고해야 한다
            logger.exception(
                "document processing failed job_id=%s attempt_no=%s",
                snapshot.job_id,
                snapshot.attempt_no,
            )
            await self._report_failure(snapshot, exc)
        finally:
            heartbeat_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await heartbeat_task

    async def _ensure_lease_active(self, snapshot: ProcessingSnapshot) -> None:
        result = await self._booth_access_client.check(
            booth_id=snapshot.booth_id, agent_id=snapshot.agent_id
        )
        if not result.allowed:
            raise BoothLeaseExpiredError(result.denial_code or "BOOTH_LEASE_EXPIRED")

    async def _send_and_finalize(
        self, snapshot: ProcessingSnapshot, chunks: tuple[EmbeddedChunk, ...]
    ) -> None:
        for batch_seq, batch in enumerate(self._split_into_batches(chunks)):
            await self._result_client.chunk_batch(
                job_id=snapshot.job_id,
                attempt_no=snapshot.attempt_no,
                batch_seq=batch_seq,
                chunks=[_to_chunk_batch_item(chunk) for chunk in batch],
            )
        await self._ensure_lease_active(snapshot)
        await self._result_client.finalize(
            job_id=snapshot.job_id,
            attempt_no=snapshot.attempt_no,
            source_hash=snapshot.source_hash,
            total_chunk_count=len(chunks),
            embedding_model_id=chunks[0].embedding_model_id,
        )

    async def _report_failure(
        self, snapshot: ProcessingSnapshot, exc: Exception
    ) -> None:
        outcome = classify_failure(exc)
        try:
            await self._result_client.failed(
                job_id=snapshot.job_id,
                attempt_no=snapshot.attempt_no,
                failure_code=outcome.code,
                retryable=outcome.retryable,
            )
        except (*_ATTEMPT_LOST_ERRORS, SpringDocumentResultUnavailable):
            # Job을 이미 잃었거나 Spring이 응답하지 않는다 — Spring의 lease
            # sweeper가 만료된 attempt를 회수해 재시도/DEAD 판정을 대신한다.
            logger.warning(
                "could not report failure to Spring job_id=%s attempt_no=%s code=%s",
                snapshot.job_id,
                snapshot.attempt_no,
                outcome.code,
            )

    async def _heartbeat_loop(self, snapshot: ProcessingSnapshot) -> None:
        while True:
            await asyncio.sleep(self._heartbeat_interval_seconds)
            try:
                await self._result_client.heartbeat(
                    job_id=snapshot.job_id, attempt_no=snapshot.attempt_no
                )
            except _ATTEMPT_LOST_ERRORS:
                return
            except SpringDocumentResultUnavailable:
                logger.warning(
                    "heartbeat failed job_id=%s attempt_no=%s",
                    snapshot.job_id,
                    snapshot.attempt_no,
                )

    def _split_into_batches(
        self, chunks: Sequence[EmbeddedChunk]
    ) -> list[list[EmbeddedChunk]]:
        batches: list[list[EmbeddedChunk]] = []
        current: list[EmbeddedChunk] = []
        current_bytes = 0
        for chunk in chunks:
            size = _estimate_json_bytes(chunk)
            if current and (
                len(current) >= self._max_chunks_per_batch
                or current_bytes + size > self._max_batch_bytes
            ):
                batches.append(current)
                current = []
                current_bytes = 0
            current.append(chunk)
            current_bytes += size
        if current:
            batches.append(current)
        return batches


def _to_chunk_batch_item(chunk: EmbeddedChunk) -> ChunkBatchItem:
    return ChunkBatchItem(
        chunk_no=chunk.chunk_no,
        content=chunk.content,
        embedding=chunk.embedding,
        embedding_model_id=chunk.embedding_model_id,
        page_number=chunk.page_number,
        section=chunk.section,
    )


def _estimate_json_bytes(chunk: EmbeddedChunk) -> int:
    return len(
        json.dumps(
            {
                "chunkNo": chunk.chunk_no,
                "content": chunk.content,
                "embedding": list(chunk.embedding),
                "embeddingModelId": chunk.embedding_model_id,
                "pageNumber": chunk.page_number,
                "section": chunk.section,
            },
            ensure_ascii=False,
        ).encode("utf-8")
    )
