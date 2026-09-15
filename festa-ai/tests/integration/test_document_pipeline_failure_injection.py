"""문서 파이프라인 장애 주입·복구 테스트 (S15P21A604-162, spec 007 SC-008).

FastAPI는 문서 Job/lease/sweeper를 소유하지 않는다 — Spring이 heartbeat 만료로
attempt를 회수해 재시도를 발급한다(document_processing_orchestrator.py,
S15P21A604-449). 그래서 AI 쪽 "복구"의 기준은 스스로 재시도를 흉내 내는 것이
아니라: (1) 어느 단계에서 강제 종료돼도 절대 멈춰 죽거나 중복·오손 보고를
하지 않고 (2) Spring이 다음 결정(재시도 또는 최종 실패)을 내릴 수 있는 상태로
조용히 남는 것이다.

`DocumentTaskSupervisor` + `DocumentProcessingOrchestrator` 조합에 6가지 장애를
주입하고(Worker 강제 종료 3단계, heartbeat 유실, finalize 콜백 유실, Job 소멸),
전부가 이 기준을 만족하는지(복구율 100%, SC-008) 검증한 뒤 최종 상태표를
`tests/validation/failure-injection-results.md`에 기록한다.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from pathlib import Path

import pytest

from app.api.schemas.documents import DocumentContentType, StorageProvider
from app.clients.spring_booth_access import BoothAccessResult
from app.clients.spring_document_result import (
    SpringDocumentResultJobGone,
    SpringDocumentResultUnavailable,
)
from app.services.document_processing_orchestrator import DocumentProcessingOrchestrator
from app.services.document_processing_service import EmbeddedChunk, ProcessingSnapshot
from app.workers.document_task_supervisor import DocumentTaskSupervisor, SubmitResult

pytestmark = pytest.mark.failure_injection

_ALLOWED = BoothAccessResult(allowed=True, lease_ends_at="2026-09-07T12:00:00Z", denial_code=None)

_RESULTS_PATH = (
    Path(__file__).resolve().parents[1] / "validation" / "failure-injection-results.md"
)


def _snapshot(job_id: int = 601, attempt_no: int = 0) -> ProcessingSnapshot:
    return ProcessingSnapshot(
        job_id=job_id,
        attempt_no=attempt_no,
        document_id=9101,
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


def _chunk(chunk_no: int) -> EmbeddedChunk:
    return EmbeddedChunk(
        chunk_no=chunk_no,
        content=f"chunk-{chunk_no}",
        embedding=(0.1, 0.2, 0.3),
        embedding_model_id="fake-embedding-v1",
        page_number=1,
        section=None,
    )


class _FakeBoothAccessClient:
    async def check(self, *, booth_id: int, agent_id: int) -> BoothAccessResult:
        return _ALLOWED


class _HangingEmbeddingService:
    """`reached`가 set될 때까지 계산 단계에서 멈춘 채, 외부에서 강제 종료를 주입받는다."""

    def __init__(self) -> None:
        self.reached = asyncio.Event()

    async def compute_embedded_chunks(self, snapshot: ProcessingSnapshot):
        self.reached.set()
        await asyncio.Event().wait()  # 프로세스가 죽을 때까지 attempt는 여기서 멈춰 있다


class _FakeEmbeddingService:
    def __init__(self, chunks: tuple[EmbeddedChunk, ...]) -> None:
        self._chunks = chunks

    async def compute_embedded_chunks(self, snapshot: ProcessingSnapshot):
        return self._chunks


class _InjectableResultClient:
    """단계별 checkpoint에서 멈추거나 지정된 예외를 던지는 결과 전달 client."""

    def __init__(
        self,
        *,
        hang_at: str | None = None,
        raise_at: dict[str, Exception] | None = None,
    ) -> None:
        self._hang_at = hang_at
        self._raise_at = raise_at or {}
        self.reached: asyncio.Event = asyncio.Event()
        self.chunk_batches: list[tuple[int, list]] = []
        self.finalize_calls: list[dict] = []
        self.heartbeats: list[tuple[int, int]] = []
        self.failed_calls: list[dict] = []

    async def _checkpoint(self, name: str) -> None:
        if name in self._raise_at:
            raise self._raise_at[name]
        if name == self._hang_at:
            self.reached.set()
            await asyncio.Event().wait()

    async def chunk_batch(self, *, job_id, attempt_no, batch_seq, chunks):
        await self._checkpoint("chunk_batch")
        self.chunk_batches.append((batch_seq, list(chunks)))

    async def finalize(self, **kwargs):
        await self._checkpoint("finalize")
        self.finalize_calls.append(kwargs)

    async def heartbeat(self, *, job_id, attempt_no):
        await self._checkpoint("heartbeat")
        self.heartbeats.append((job_id, attempt_no))

    async def failed(self, **kwargs):
        await self._checkpoint("failed")
        self.failed_calls.append(kwargs)


@dataclass
class ScenarioResult:
    name: str
    injection_point: str
    recovered: bool
    detail: str


def _build_supervisor(
    *,
    embedding_service,
    result_client,
    heartbeat_interval_seconds: float = 60.0,
) -> tuple[DocumentTaskSupervisor, DocumentProcessingOrchestrator]:
    orchestrator = DocumentProcessingOrchestrator(
        embedding_service=embedding_service,
        result_client=result_client,
        booth_access_client=_FakeBoothAccessClient(),
        heartbeat_interval_seconds=heartbeat_interval_seconds,
    )
    supervisor = DocumentTaskSupervisor(processor=orchestrator.run, max_concurrency=2)
    return supervisor, orchestrator


async def _settle() -> None:
    await asyncio.sleep(0)
    await asyncio.sleep(0)


async def _wait_until_idle(supervisor: DocumentTaskSupervisor, *, timeout: float = 1.0) -> None:
    """attempt task와 그 done_callback이 전부 정리될 때까지 event loop에 양보한다."""
    async with asyncio.timeout(timeout):
        while supervisor.active_count > 0:
            await asyncio.sleep(0)


# ---------------------------------------------------------------------------
# 시나리오 1: 계산(Parsing/Embedding) 단계에서 Worker 강제 종료
# ---------------------------------------------------------------------------


async def _run_kill_during_computation() -> ScenarioResult:
    embedding_service = _HangingEmbeddingService()
    result_client = _InjectableResultClient()
    supervisor, _ = _build_supervisor(
        embedding_service=embedding_service, result_client=result_client
    )
    snapshot = _snapshot(job_id=611)

    assert supervisor.submit(snapshot) is SubmitResult.ACCEPTED
    await embedding_service.reached.wait()

    supervisor.cancel(job_id=snapshot.job_id, attempt_no=snapshot.attempt_no)
    await _wait_until_idle(supervisor)

    recovered = (
        supervisor.active_count == 0
        and result_client.chunk_batches == []
        and result_client.finalize_calls == []
        and result_client.failed_calls == []
    )
    return ScenarioResult(
        name="parsing_embedding_kill",
        injection_point="compute_embedded_chunks 진행 중",
        recovered=recovered,
        detail="attempt 취소, 전송·finalize·failed 호출 없음 — Spring lease sweeper가 회수",
    )


@pytest.mark.asyncio
async def test_worker_killed_during_computation_leaves_no_partial_report() -> None:
    result = await _run_kill_during_computation()
    assert result.recovered, result.detail


# ---------------------------------------------------------------------------
# 시나리오 2: Chunk 전송(commit) 단계에서 Worker 강제 종료
# ---------------------------------------------------------------------------


async def _run_kill_during_chunk_send() -> ScenarioResult:
    chunks = tuple(_chunk(i) for i in range(3))
    embedding_service = _FakeEmbeddingService(chunks=chunks)
    result_client = _InjectableResultClient(hang_at="chunk_batch")
    supervisor, _ = _build_supervisor(
        embedding_service=embedding_service, result_client=result_client
    )
    snapshot = _snapshot(job_id=612)

    assert supervisor.submit(snapshot) is SubmitResult.ACCEPTED
    await result_client.reached.wait()

    supervisor.cancel(job_id=snapshot.job_id, attempt_no=snapshot.attempt_no)
    await _wait_until_idle(supervisor)

    recovered = (
        supervisor.active_count == 0
        and result_client.chunk_batches == []
        and result_client.finalize_calls == []
        and result_client.failed_calls == []
    )
    return ScenarioResult(
        name="chunk_send_kill",
        injection_point="chunk_batch 전송 중",
        recovered=recovered,
        detail="batch 미완료 상태로 취소 — 부분 전송이 finalize로 이어지지 않음",
    )


@pytest.mark.asyncio
async def test_worker_killed_during_chunk_send_leaves_no_partial_report() -> None:
    result = await _run_kill_during_chunk_send()
    assert result.recovered, result.detail


# ---------------------------------------------------------------------------
# 시나리오 3: finalize 직전(모든 chunk 전송 후) Worker 강제 종료
# ---------------------------------------------------------------------------


async def _run_kill_before_finalize() -> ScenarioResult:
    chunks = (_chunk(0),)
    embedding_service = _FakeEmbeddingService(chunks=chunks)
    result_client = _InjectableResultClient(hang_at="finalize")
    supervisor, _ = _build_supervisor(
        embedding_service=embedding_service, result_client=result_client
    )
    snapshot = _snapshot(job_id=613)

    assert supervisor.submit(snapshot) is SubmitResult.ACCEPTED
    await result_client.reached.wait()

    supervisor.cancel(job_id=snapshot.job_id, attempt_no=snapshot.attempt_no)
    await _wait_until_idle(supervisor)

    recovered = (
        supervisor.active_count == 0
        and len(result_client.chunk_batches) == 1
        and result_client.finalize_calls == []
        and result_client.failed_calls == []
    )
    return ScenarioResult(
        name="pre_finalize_kill",
        injection_point="finalize 직전(lease 재확인 후)",
        recovered=recovered,
        detail="chunk는 전달됐지만 finalize 미완료 — READY 오상태 전이 없음",
    )


@pytest.mark.asyncio
async def test_worker_killed_before_finalize_never_reports_ready() -> None:
    result = await _run_kill_before_finalize()
    assert result.recovered, result.detail


# ---------------------------------------------------------------------------
# 시나리오 4: heartbeat 전달이 계속 실패해도 처리는 끝까지 진행된다
# ---------------------------------------------------------------------------


async def _run_heartbeat_delivery_loss() -> ScenarioResult:
    class _SlowEmbeddingService:
        async def compute_embedded_chunks(self, snapshot: ProcessingSnapshot):
            await asyncio.sleep(0.05)
            return (_chunk(0),)

    result_client = _InjectableResultClient(
        raise_at={"heartbeat": SpringDocumentResultUnavailable("heartbeat unreachable")}
    )
    supervisor, _ = _build_supervisor(
        embedding_service=_SlowEmbeddingService(),
        result_client=result_client,
        heartbeat_interval_seconds=0.01,
    )
    snapshot = _snapshot(job_id=614)

    assert supervisor.submit(snapshot) is SubmitResult.ACCEPTED
    await asyncio.sleep(0.1)
    await _settle()

    recovered = (
        supervisor.active_count == 0
        and len(result_client.finalize_calls) == 1
        and result_client.failed_calls == []
    )
    return ScenarioResult(
        name="heartbeat_undelivered",
        injection_point="heartbeat 전송(30초 주기, 테스트는 0.01초)",
        recovered=recovered,
        detail="heartbeat 유실이 처리 자체를 막지 않고 finalize까지 정상 도달",
    )


@pytest.mark.asyncio
async def test_heartbeat_loss_does_not_abort_processing() -> None:
    result = await _run_heartbeat_delivery_loss()
    assert result.recovered, result.detail


# ---------------------------------------------------------------------------
# 시나리오 5: finalize 콜백 자체가 유실된다 (Spring 응답 없음)
# ---------------------------------------------------------------------------


async def _run_finalize_callback_lost() -> ScenarioResult:
    unavailable = SpringDocumentResultUnavailable("spring unreachable")
    result_client = _InjectableResultClient(
        raise_at={"finalize": unavailable, "failed": unavailable}
    )
    embedding_service = _FakeEmbeddingService(chunks=(_chunk(0),))
    supervisor, _ = _build_supervisor(
        embedding_service=embedding_service, result_client=result_client
    )
    snapshot = _snapshot(job_id=615)

    assert supervisor.submit(snapshot) is SubmitResult.ACCEPTED
    await _wait_until_idle(supervisor)

    recovered = (
        supervisor.active_count == 0
        and result_client.finalize_calls == []
        and result_client.failed_calls == []
    )
    return ScenarioResult(
        name="finalize_callback_lost",
        injection_point="finalize 응답 및 뒤이은 failed 재보고 모두 실패",
        recovered=recovered,
        detail="예외 없이 UNDELIVERED로 종료 — Spring lease 만료로 재시도 위임",
    )


@pytest.mark.asyncio
async def test_finalize_callback_loss_does_not_crash_worker(caplog) -> None:
    with caplog.at_level(
        logging.WARNING, logger="app.services.document_processing_orchestrator"
    ):
        result = await _run_finalize_callback_lost()
    assert result.recovered, result.detail
    assert any(
        record.getMessage() == "spring_failure_callback_undelivered"
        for record in caplog.records
    )


# ---------------------------------------------------------------------------
# 시나리오 6: finalize 시점에 Job이 이미 소멸(410) — 재시도 상한 도달 후 최종 종료
# ---------------------------------------------------------------------------


async def _run_job_gone_at_finalize() -> ScenarioResult:
    result_client = _InjectableResultClient(
        raise_at={"finalize": SpringDocumentResultJobGone("job gone")}
    )
    embedding_service = _FakeEmbeddingService(chunks=(_chunk(0),))
    supervisor, _ = _build_supervisor(
        embedding_service=embedding_service, result_client=result_client
    )
    snapshot = _snapshot(job_id=616)

    assert supervisor.submit(snapshot) is SubmitResult.ACCEPTED
    await _wait_until_idle(supervisor)

    recovered = (
        supervisor.active_count == 0
        and result_client.finalize_calls == []
        and result_client.failed_calls == []
    )
    return ScenarioResult(
        name="job_gone_at_finalize",
        injection_point="finalize 호출이 410(Job 소멸)로 거부됨",
        recovered=recovered,
        detail="failed 재보고 없이 SUPERSEDED로 조용히 종료 — 중복 보고 0건",
    )


@pytest.mark.asyncio
async def test_job_gone_at_finalize_stops_without_duplicate_report() -> None:
    result = await _run_job_gone_at_finalize()
    assert result.recovered, result.detail


# ---------------------------------------------------------------------------
# 전체 6종 일괄 실행 → 최종 상태표 (SC-008: 복구율 100%)
# ---------------------------------------------------------------------------

_SCENARIOS = (
    _run_kill_during_computation,
    _run_kill_during_chunk_send,
    _run_kill_before_finalize,
    _run_heartbeat_delivery_loss,
    _run_finalize_callback_lost,
    _run_job_gone_at_finalize,
)


def _render_results_table(results: list[ScenarioResult]) -> str:
    recovered_count = sum(1 for r in results if r.recovered)
    lines = [
        "# 문서 파이프라인 장애 주입·복구 결과 (S15P21A604-162)",
        "",
        f"복구율: {recovered_count}/{len(results)} "
        f"({recovered_count * 100 // len(results)}%)",
        "",
        "| 시나리오 | 주입 지점 | 결과 | 비고 |",
        "|---|---|---|---|",
    ]
    for r in results:
        status = "PASS" if r.recovered else "FAIL"
        lines.append(f"| {r.name} | {r.injection_point} | {status} | {r.detail} |")
    lines.append("")
    return "\n".join(lines)


@pytest.mark.asyncio
async def test_injection_scenario_matrix_recovers_100_percent() -> None:
    results = [await scenario() for scenario in _SCENARIOS]

    _RESULTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    _RESULTS_PATH.write_text(_render_results_table(results), encoding="utf-8")

    failures = [r for r in results if not r.recovered]
    assert not failures, f"복구 실패 시나리오: {[r.name for r in failures]}"
