"""Verify the Worker publishes results only while it owns the Job lease."""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from dataclasses import dataclass

import pytest

from app.workers.document_worker import DocumentWorker, WorkerRunState


@dataclass
class FakeJob:
    id: int = 1


class FakeRepository:
    def __init__(
        self,
        *,
        heartbeat_results: list[bool],
        pickup_job: FakeJob | None = None,
    ) -> None:
        self._heartbeat_results = iter(heartbeat_results)
        self._pickup_job = FakeJob() if pickup_job is None else pickup_job

    async def pickup(self, *, worker_id: str, lease_seconds: int):
        return self._pickup_job

    async def heartbeat(self, *, job_id: int, worker_id: str, lease_seconds: int) -> bool:
        return next(self._heartbeat_results)


def _repository_factory(repository: FakeRepository):
    @asynccontextmanager
    async def factory():
        yield repository

    return factory


@pytest.mark.asyncio
async def test_worker_publishes_result_after_final_ownership_check() -> None:
    repository = FakeRepository(heartbeat_results=[True])
    published: list[str] = []
    worker = DocumentWorker(
        repository_factory=_repository_factory(repository),
        worker_id="worker-a",
        lease_seconds=90,
        heartbeat_seconds=30,
    )

    result = await worker.run_once(
        process=lambda job: _return("processed"),
        publish=lambda job, value, worker_id: _append(published, value),
    )

    assert result.state is WorkerRunState.PUBLISHED
    assert published == ["processed"]


@pytest.mark.asyncio
async def test_worker_discards_result_when_final_heartbeat_loses_ownership() -> None:
    repository = FakeRepository(heartbeat_results=[False])
    published: list[str] = []
    worker = DocumentWorker(
        repository_factory=_repository_factory(repository),
        worker_id="worker-a",
        lease_seconds=90,
        heartbeat_seconds=30,
    )

    result = await worker.run_once(
        process=lambda job: _return("must-not-publish"),
        publish=lambda job, value, worker_id: _append(published, value),
    )

    assert result.state is WorkerRunState.LEASE_LOST
    assert published == []


@pytest.mark.asyncio
async def test_periodic_heartbeat_loss_discards_in_flight_result() -> None:
    repository = FakeRepository(heartbeat_results=[False])
    published: list[str] = []
    worker = DocumentWorker(
        repository_factory=_repository_factory(repository),
        worker_id="worker-a",
        lease_seconds=90,
        heartbeat_seconds=0.01,
    )

    async def slow_process(job: FakeJob) -> str:
        await asyncio.sleep(0.03)
        return "stale-result"

    result = await worker.run_once(
        process=slow_process,
        publish=lambda job, value, worker_id: _append(published, value),
    )

    assert result.state is WorkerRunState.LEASE_LOST
    assert published == []


async def _return(value: str) -> str:
    return value


async def _append(values: list[str], value: str) -> None:
    values.append(value)
