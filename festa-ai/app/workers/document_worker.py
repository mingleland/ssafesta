"""Claim document Jobs, keep their DB lease alive, and discard results after ownership loss."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from enum import Enum
from typing import AsyncContextManager, Protocol, TypeVar

ResultT = TypeVar("ResultT")


class LeaseJob(Protocol):
    id: int


class LeaseRepository(Protocol):
    async def pickup(self, *, worker_id: str, lease_seconds: int) -> LeaseJob | None: ...

    async def heartbeat(self, *, job_id: int, worker_id: str, lease_seconds: int) -> bool: ...


RepositoryFactory = Callable[[], AsyncContextManager[LeaseRepository]]
Processor = Callable[[LeaseJob], Awaitable[ResultT]]
Publisher = Callable[[LeaseJob, ResultT, str], Awaitable[None]]


class WorkerRunState(str, Enum):
    IDLE = "IDLE"
    PUBLISHED = "PUBLISHED"
    LEASE_LOST = "LEASE_LOST"


@dataclass(frozen=True, slots=True)
class WorkerRunResult:
    state: WorkerRunState
    job_id: int | None = None


class DocumentWorker:
    """Run one durable Job at a time while heartbeats protect its ownership."""

    def __init__(
        self,
        *,
        repository_factory: RepositoryFactory,
        worker_id: str,
        lease_seconds: int,
        heartbeat_seconds: float,
    ) -> None:
        self._repository_factory = repository_factory
        self._worker_id = worker_id
        self._lease_seconds = lease_seconds
        self._heartbeat_seconds = heartbeat_seconds

    async def run(
        self,
        *,
        process: Processor[ResultT],
        publish: Publisher[ResultT],
        stop: asyncio.Event,
        idle_seconds: float = 1.0,
    ) -> None:
        """Poll until shutdown, backing off briefly when no Job is runnable."""
        while not stop.is_set():
            result = await self.run_once(process=process, publish=publish)
            if result.state is not WorkerRunState.IDLE:
                continue
            try:
                await asyncio.wait_for(stop.wait(), timeout=idle_seconds)
            except TimeoutError:
                pass

    async def run_once(
        self,
        *,
        process: Processor[ResultT],
        publish: Publisher[ResultT],
    ) -> WorkerRunResult:
        async with self._repository_factory() as repository:
            job = await repository.pickup(
                worker_id=self._worker_id,
                lease_seconds=self._lease_seconds,
            )
        if job is None:
            return WorkerRunResult(WorkerRunState.IDLE)

        stop_heartbeat = asyncio.Event()
        lease_lost = asyncio.Event()
        heartbeat_task = asyncio.create_task(
            self._heartbeat_loop(
                job_id=job.id,
                stop=stop_heartbeat,
                lease_lost=lease_lost,
            )
        )
        try:
            result = await process(job)
        finally:
            stop_heartbeat.set()
            await heartbeat_task

        if lease_lost.is_set() or not await self._renew_lease(job.id):
            return WorkerRunResult(WorkerRunState.LEASE_LOST, job.id)

        # The concrete publisher must condition its final DB transaction on
        # (job_id, worker_id, RUNNING, valid lease). Passing worker_id makes
        # that ownership guard mandatory at the integration boundary.
        await publish(job, result, self._worker_id)
        return WorkerRunResult(WorkerRunState.PUBLISHED, job.id)

    async def _heartbeat_loop(
        self,
        *,
        job_id: int,
        stop: asyncio.Event,
        lease_lost: asyncio.Event,
    ) -> None:
        while not stop.is_set():
            try:
                await asyncio.wait_for(stop.wait(), timeout=self._heartbeat_seconds)
                continue
            except TimeoutError:
                pass

            if not await self._renew_lease(job_id):
                lease_lost.set()
                return

    async def _renew_lease(self, job_id: int) -> bool:
        async with self._repository_factory() as repository:
            return await repository.heartbeat(
                job_id=job_id,
                worker_id=self._worker_id,
                lease_seconds=self._lease_seconds,
            )
