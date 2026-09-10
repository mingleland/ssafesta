"""문서 처리 attempt의 중복 실행·동시성·종료를 FastAPI 프로세스 수명에 맞춰 관리한다."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from enum import Enum
from typing import Protocol, TypeVar

logger = logging.getLogger(__name__)

RequestT = TypeVar("RequestT", contravariant=True)


class DocumentAttempt(Protocol):
    """Spring이 권위를 갖는 처리 attempt의 최소 식별 계약."""

    job_id: int
    attempt_no: int


Processor = Callable[[RequestT], Awaitable[None]]


@dataclass(frozen=True, slots=True)
class AttemptKey:
    job_id: int
    attempt_no: int


class SubmitResult(str, Enum):
    ACCEPTED = "ACCEPTED"
    DUPLICATE = "DUPLICATE"


class SupervisorClosedError(RuntimeError):
    """종료를 시작한 프로세스가 새 attempt를 받으려 할 때 발생한다."""


class DocumentTaskSupervisor:
    """한 프로세스 안에서 attempt를 한 번만, 제한된 동시성으로 실행한다.

    Job과 재시도의 Source of Truth는 Spring이다. 이 객체는 진행 상태를 영속화하지
    않는다. 프로세스가 죽거나 종료 제한시간을 넘겨 작업이 취소되면 heartbeat가
    끊기고, Spring lease sweeper가 새 attempt를 발급한다.
    """

    def __init__(
        self,
        *,
        processor: Processor[DocumentAttempt],
        max_concurrency: int,
    ) -> None:
        if max_concurrency <= 0:
            raise ValueError("max_concurrency must be positive")
        self._processor = processor
        self._slots = asyncio.Semaphore(max_concurrency)
        self._tasks: dict[AttemptKey, asyncio.Task[None]] = {}
        self._cancel_requested: set[AttemptKey] = set()
        self._accepting = True

    @property
    def accepting(self) -> bool:
        return self._accepting

    @property
    def active_count(self) -> int:
        return len(self._tasks)

    def submit(self, attempt: DocumentAttempt) -> SubmitResult:
        """현재 event loop에 attempt를 등록한다. 같은 키는 멱등하게 수락한다."""
        if not self._accepting:
            raise SupervisorClosedError("document worker is shutting down")

        key = AttemptKey(job_id=attempt.job_id, attempt_no=attempt.attempt_no)
        if key in self._tasks:
            return SubmitResult.DUPLICATE

        task = asyncio.create_task(self._run(key, attempt))
        self._tasks[key] = task
        task.add_done_callback(lambda completed, attempt_key=key: self._finished(attempt_key, completed))
        return SubmitResult.ACCEPTED

    def cancel(self, *, job_id: int, attempt_no: int) -> None:
        """실행 중인 attempt를 취소 요청한다. 대상이 없어도 조용히 성공한다(멱등).

        키가 정확히 일치하는 attempt만 취소한다 — 늦게 도착한 cancel이 lease
        만료로 새로 발급된 다음 attempt를 죽이지 않는다.
        """
        key = AttemptKey(job_id=job_id, attempt_no=attempt_no)
        task = self._tasks.get(key)
        if task is None or task.done():
            return
        self._cancel_requested.add(key)
        task.cancel()

    async def close(self, *, grace_seconds: float) -> None:
        """새 작업을 막고 제한시간까지 기다린 뒤 남은 작업을 취소한다."""
        if grace_seconds < 0:
            raise ValueError("grace_seconds must not be negative")
        self._accepting = False
        pending = tuple(self._tasks.values())
        if not pending:
            return

        _, still_running = await asyncio.wait(pending, timeout=grace_seconds)
        for task in still_running:
            task.cancel()
        if still_running:
            await asyncio.gather(*still_running, return_exceptions=True)

    async def _run(self, key: AttemptKey, attempt: DocumentAttempt) -> None:
        async with self._slots:
            await self._processor(attempt)

    def _finished(self, key: AttemptKey, task: asyncio.Task[None]) -> None:
        self._tasks.pop(key, None)
        was_requested = key in self._cancel_requested
        self._cancel_requested.discard(key)
        if task.cancelled():
            if was_requested:
                logger.info(
                    "Document attempt cancelled by request: job_id=%s attempt_no=%s",
                    key.job_id,
                    key.attempt_no,
                )
            else:
                logger.warning(
                    "Document attempt cancelled; Spring lease recovery required: job_id=%s attempt_no=%s",
                    key.job_id,
                    key.attempt_no,
                )
            return
        error = task.exception()
        if error is not None:
            logger.error(
                "Document attempt failed: job_id=%s attempt_no=%s",
                key.job_id,
                key.attempt_no,
                exc_info=(type(error), error, error.__traceback__),
            )
