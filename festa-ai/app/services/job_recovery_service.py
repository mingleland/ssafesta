"""Run the periodic sweeper that recovers Jobs abandoned by dead Workers."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from dataclasses import dataclass
from typing import AsyncContextManager, Protocol

from app.repositories.document_job_repository import RecoveryResult


class RecoveryRepository(Protocol):
    async def recover_expired(
        self, *, backoff_seconds: tuple[int, ...]
    ) -> RecoveryResult | None: ...


RepositoryFactory = Callable[[], AsyncContextManager[RecoveryRepository]]


@dataclass(frozen=True, slots=True)
class SweepResult:
    retried: int = 0
    dead: int = 0


class JobRecoveryService:
    """Sweep immediately on startup, then periodically until shutdown."""

    def __init__(
        self,
        *,
        repository_factory: RepositoryFactory,
        backoff_seconds: tuple[int, ...],
        sweep_seconds: float,
    ) -> None:
        if sweep_seconds <= 0:
            raise ValueError("sweep_seconds must be positive")
        if not backoff_seconds or any(delay <= 0 for delay in backoff_seconds):
            raise ValueError("backoff_seconds must contain positive delays")
        self._repository_factory = repository_factory
        self._backoff_seconds = backoff_seconds
        self._sweep_seconds = sweep_seconds

    async def sweep_once(self) -> SweepResult:
        """Drain every Job that was expired when this sweep observed it."""
        retried = 0
        dead = 0
        while True:
            async with self._repository_factory() as repository:
                recovered = await repository.recover_expired(
                    backoff_seconds=self._backoff_seconds
                )
            if recovered is None:
                return SweepResult(retried=retried, dead=dead)
            if recovered.status == "RETRY_WAIT":
                retried += 1
            elif recovered.status == "DEAD":
                dead += 1
            else:  # pragma: no cover - repository contract violation
                raise RuntimeError(
                    f"Unexpected recovered Job status: {recovered.status}"
                )

    async def run(self, *, stop: asyncio.Event) -> None:
        """Sweep now and after each configured interval until stopped."""
        while not stop.is_set():
            await self.sweep_once()
            try:
                await asyncio.wait_for(stop.wait(), timeout=self._sweep_seconds)
            except TimeoutError:
                pass
