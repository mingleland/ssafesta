"""문서 Worker supervisor의 중복 방지·동시성·graceful shutdown을 검증한다."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

import pytest

from app.workers.document_task_supervisor import (
    DocumentTaskSupervisor,
    SubmitResult,
    SupervisorClosedError,
)


@dataclass(frozen=True)
class Attempt:
    job_id: int
    attempt_no: int


async def _settle() -> None:
    """완료된 task의 done_callback(call_soon으로 예약됨)까지 실행되도록 두 틱 양보한다."""
    await asyncio.sleep(0)
    await asyncio.sleep(0)


@pytest.mark.asyncio
async def test_duplicate_attempt_runs_once() -> None:
    release = asyncio.Event()
    calls: list[Attempt] = []

    async def process(attempt: Attempt) -> None:
        calls.append(attempt)
        await release.wait()

    supervisor = DocumentTaskSupervisor(processor=process, max_concurrency=1)
    attempt = Attempt(job_id=41, attempt_no=2)

    assert supervisor.submit(attempt) is SubmitResult.ACCEPTED
    assert supervisor.submit(attempt) is SubmitResult.DUPLICATE
    await asyncio.sleep(0)
    assert calls == [attempt]

    release.set()
    await supervisor.close(grace_seconds=1)
    assert supervisor.active_count == 0


@pytest.mark.asyncio
async def test_concurrency_is_bounded() -> None:
    release = asyncio.Event()
    running = 0
    peak = 0

    async def process(attempt: Attempt) -> None:
        nonlocal running, peak
        running += 1
        peak = max(peak, running)
        await release.wait()
        running -= 1

    supervisor = DocumentTaskSupervisor(processor=process, max_concurrency=2)
    for job_id in range(1, 5):
        assert supervisor.submit(Attempt(job_id, 0)) is SubmitResult.ACCEPTED

    await asyncio.sleep(0)
    assert peak == 2
    release.set()
    await supervisor.close(grace_seconds=1)


@pytest.mark.asyncio
async def test_close_waits_for_work_within_grace_period() -> None:
    finished = asyncio.Event()

    async def process(attempt: Attempt) -> None:
        await asyncio.sleep(0)
        finished.set()

    supervisor = DocumentTaskSupervisor(processor=process, max_concurrency=1)
    supervisor.submit(Attempt(1, 0))

    await supervisor.close(grace_seconds=1)

    assert finished.is_set()
    assert supervisor.active_count == 0
    assert not supervisor.accepting


@pytest.mark.asyncio
async def test_close_cancels_work_after_grace_period() -> None:
    cancelled = asyncio.Event()

    async def process(attempt: Attempt) -> None:
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            cancelled.set()
            raise

    supervisor = DocumentTaskSupervisor(processor=process, max_concurrency=1)
    supervisor.submit(Attempt(1, 0))
    await asyncio.sleep(0)

    await supervisor.close(grace_seconds=0)

    assert cancelled.is_set()
    assert supervisor.active_count == 0


@pytest.mark.asyncio
async def test_close_rejects_new_attempts() -> None:
    async def process(attempt: Attempt) -> None:
        return None

    supervisor = DocumentTaskSupervisor(processor=process, max_concurrency=1)
    await supervisor.close(grace_seconds=0)

    with pytest.raises(SupervisorClosedError):
        supervisor.submit(Attempt(1, 0))


@pytest.mark.asyncio
async def test_cancel_stops_running_attempt_with_matching_key() -> None:
    cancelled = asyncio.Event()

    async def process(attempt: Attempt) -> None:
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            cancelled.set()
            raise

    supervisor = DocumentTaskSupervisor(processor=process, max_concurrency=1)
    supervisor.submit(Attempt(job_id=1, attempt_no=3))
    await asyncio.sleep(0)

    supervisor.cancel(job_id=1, attempt_no=3)
    await _settle()

    assert cancelled.is_set()
    assert supervisor.active_count == 0


@pytest.mark.asyncio
async def test_cancel_ignores_mismatched_attempt_no() -> None:
    cancelled = asyncio.Event()

    async def process(attempt: Attempt) -> None:
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            cancelled.set()
            raise

    supervisor = DocumentTaskSupervisor(processor=process, max_concurrency=1)
    supervisor.submit(Attempt(job_id=1, attempt_no=3))
    await asyncio.sleep(0)

    supervisor.cancel(job_id=1, attempt_no=2)
    await asyncio.sleep(0)

    assert not cancelled.is_set()
    assert supervisor.active_count == 1

    await supervisor.close(grace_seconds=0)


@pytest.mark.asyncio
async def test_cancel_unknown_job_is_a_noop() -> None:
    async def process(attempt: Attempt) -> None:
        return None

    supervisor = DocumentTaskSupervisor(processor=process, max_concurrency=1)

    supervisor.cancel(job_id=999, attempt_no=0)

    assert supervisor.active_count == 0


@pytest.mark.asyncio
async def test_cancel_already_finished_attempt_is_a_noop() -> None:
    async def process(attempt: Attempt) -> None:
        return None

    supervisor = DocumentTaskSupervisor(processor=process, max_concurrency=1)
    supervisor.submit(Attempt(job_id=1, attempt_no=0))
    await _settle()
    assert supervisor.active_count == 0

    supervisor.cancel(job_id=1, attempt_no=0)

    assert supervisor.active_count == 0
