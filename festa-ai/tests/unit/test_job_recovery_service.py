"""Verify the recovery sweeper runs immediately and drains expired Jobs."""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

import pytest

from app.repositories.document_job_repository import RecoveryResult
from app.services.job_recovery_service import JobRecoveryService


class FakeRecoveryRepository:
    def __init__(
        self,
        results: list[RecoveryResult | None],
        *,
        stop_after_calls: tuple[asyncio.Event, int] | None = None,
    ) -> None:
        self._results = iter(results)
        self._stop_after_calls = stop_after_calls
        self.calls = 0

    async def recover_expired(self, *, backoff_seconds: tuple[int, ...]):
        self.calls += 1
        if self._stop_after_calls is not None:
            stop, target_calls = self._stop_after_calls
            if self.calls >= target_calls:
                stop.set()
        return next(self._results, None)


def _repository_factory(repository: FakeRecoveryRepository):
    @asynccontextmanager
    async def factory():
        yield repository

    return factory


@pytest.mark.asyncio
async def test_sweep_once_drains_all_expired_jobs() -> None:
    repository = FakeRecoveryRepository(
        [
            RecoveryResult(1, "RETRY_WAIT", 1, 60),
            RecoveryResult(2, "DEAD", 4, None),
            None,
        ]
    )
    service = JobRecoveryService(
        repository_factory=_repository_factory(repository),
        backoff_seconds=(60, 300, 900),
        sweep_seconds=60,
    )

    result = await service.sweep_once()

    assert result.retried == 1
    assert result.dead == 1
    assert repository.calls == 3


@pytest.mark.asyncio
async def test_run_sweeps_immediately_then_on_interval() -> None:
    stop = asyncio.Event()
    repository = FakeRecoveryRepository(
        [None, None], stop_after_calls=(stop, 2)
    )
    service = JobRecoveryService(
        repository_factory=_repository_factory(repository),
        backoff_seconds=(60, 300, 900),
        sweep_seconds=0.01,
    )

    await asyncio.wait_for(service.run(stop=stop), timeout=1)

    assert repository.calls == 2
