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
from app.core.logging import log_event
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
from app.services.context_service import ExtractedProjectFacts
from app.services.failure_policy import classify_failure, describe_failure
from app.services.project_fact_extractor import ProjectFactExtractor

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
        project_fact_extractor: ProjectFactExtractor | None = None,
        max_chunks_per_batch: int = DEFAULT_MAX_CHUNKS_PER_BATCH,
        max_batch_bytes: int = DEFAULT_MAX_BATCH_BYTES,
    ) -> None:
        self._embedding_service = embedding_service
        self._result_client = result_client
        self._booth_access_client = booth_access_client
        self._project_fact_extractor = project_fact_extractor
        self._heartbeat_interval_seconds = heartbeat_interval_seconds
        self._max_chunks_per_batch = max_chunks_per_batch
        self._max_batch_bytes = max_batch_bytes

    async def run(self, snapshot: ProcessingSnapshot) -> None:
        heartbeat_task = asyncio.create_task(self._heartbeat_loop(snapshot))
        try:
            await self._ensure_lease_active(snapshot)
            chunks = await self._embedding_service.compute_embedded_chunks(snapshot)
            project_facts = await self._extract_project_facts(snapshot, chunks)
            await self._send_and_finalize(snapshot, chunks, project_facts)
        except _ATTEMPT_LOST_ERRORS:
            log_event(
                logger,
                logging.INFO,
                "document_processing_stopped",
                job_id=snapshot.job_id,
                document_id=snapshot.document_id,
                booth_id=snapshot.booth_id,
                agent_id=snapshot.agent_id,
                attempt_no=snapshot.attempt_no,
                status="SUPERSEDED",
            )
        except Exception as exc:  # noqa: BLE001 — 모든 처리 실패를 Spring에 보고해야 한다
            outcome = classify_failure(exc)
            log_event(
                logger,
                logging.ERROR,
                "document_processing_failed",
                job_id=snapshot.job_id,
                document_id=snapshot.document_id,
                booth_id=snapshot.booth_id,
                agent_id=snapshot.agent_id,
                attempt_no=snapshot.attempt_no,
                status="FAILED",
                error_code=outcome.code,
                error_type=type(exc).__name__,
                error_detail=describe_failure(exc),
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
        self,
        snapshot: ProcessingSnapshot,
        chunks: tuple[EmbeddedChunk, ...],
        project_facts: ExtractedProjectFacts | None,
    ) -> None:
        for batch_seq, batch in enumerate(self._split_into_batches(chunks)):
            await self._result_client.chunk_batch(
                job_id=snapshot.job_id,
                attempt_no=snapshot.attempt_no,
                batch_seq=batch_seq,
                chunks=[_to_chunk_batch_item(chunk) for chunk in batch],
            )
        await self._ensure_lease_active(snapshot)
        finalize_arguments: dict[str, object] = {
            "job_id": snapshot.job_id,
            "attempt_no": snapshot.attempt_no,
            "source_hash": snapshot.source_hash,
            "total_chunk_count": len(chunks),
            "embedding_model_id": chunks[0].embedding_model_id,
        }
        if project_facts is not None:
            finalize_arguments["project_facts"] = project_facts
        await self._result_client.finalize(**finalize_arguments)

    async def _extract_project_facts(
        self,
        snapshot: ProcessingSnapshot,
        chunks: tuple[EmbeddedChunk, ...],
    ) -> ExtractedProjectFacts | None:
        if self._project_fact_extractor is None:
            return None
        try:
            return await self._project_fact_extractor.extract(chunks)
        except Exception:  # noqa: BLE001 — 최적화 실패가 문서 READY를 막아서는 안 된다
            log_event(
                logger,
                logging.WARNING,
                "project_fact_extraction_failed",
                job_id=snapshot.job_id,
                document_id=snapshot.document_id,
                booth_id=snapshot.booth_id,
                agent_id=snapshot.agent_id,
                attempt_no=snapshot.attempt_no,
                status="SKIPPED",
                error_code="PROJECT_FACT_EXTRACTION_FAILED",
            )
            return None

    async def _report_failure(
        self, snapshot: ProcessingSnapshot, exc: Exception
    ) -> None:
        outcome = classify_failure(exc)
        # 계약 거절·Spring 장애는 사유가 없으면 운영자가 코드만 보고 원인을 못 잡는다. 그 두 경우만
        # 짧은 사유를 `message` 로 함께 보낸다(last_error). 다른 코드는 코드 자체가 사유다.
        extra: dict[str, str] = {}
        if outcome.code in ("CONTRACT_REJECTED", "SPRING_RESULT_UNAVAILABLE"):
            extra["message"] = describe_failure(exc)
        try:
            await self._result_client.failed(
                job_id=snapshot.job_id,
                attempt_no=snapshot.attempt_no,
                failure_code=outcome.code,
                retryable=outcome.retryable,
                **extra,
            )
        except (*_ATTEMPT_LOST_ERRORS, SpringDocumentResultUnavailable):
            # Job을 이미 잃었거나 Spring이 응답하지 않는다 — Spring의 lease
            # sweeper가 만료된 attempt를 회수해 재시도/DEAD 판정을 대신한다.
            log_event(
                logger,
                logging.WARNING,
                "spring_failure_callback_undelivered",
                job_id=snapshot.job_id,
                document_id=snapshot.document_id,
                booth_id=snapshot.booth_id,
                agent_id=snapshot.agent_id,
                attempt_no=snapshot.attempt_no,
                status="UNDELIVERED",
                error_code=outcome.code,
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
                log_event(
                    logger,
                    logging.WARNING,
                    "spring_heartbeat_undelivered",
                    job_id=snapshot.job_id,
                    document_id=snapshot.document_id,
                    booth_id=snapshot.booth_id,
                    agent_id=snapshot.agent_id,
                    attempt_no=snapshot.attempt_no,
                    status="UNDELIVERED",
                    error_code="SPRING_UNAVAILABLE",
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
