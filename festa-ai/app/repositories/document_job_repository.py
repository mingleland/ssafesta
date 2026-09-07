"""Persist and idempotently reuse document-processing Jobs in the AI DB."""

from __future__ import annotations

import datetime
from dataclasses import dataclass

from sqlalchemy import and_, func, or_, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.schemas.documents import ProcessDocumentRequest
from app.db.models import ACTIVE_JOB_STATUSES, DocumentJob
from app.services.document_snapshot_validator import ensure_snapshot_matches


@dataclass(frozen=True, slots=True)
class EnqueueResult:
    job: DocumentJob
    existing: bool


@dataclass(frozen=True, slots=True)
class RecoveryResult:
    job_id: int
    status: str
    attempt_no: int
    backoff_seconds: int | None


class DocumentJobRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_active(self, document_id: int) -> DocumentJob | None:
        statement = (
            select(DocumentJob)
            .where(
                DocumentJob.document_id == document_id,
                DocumentJob.status.in_(ACTIVE_JOB_STATUSES),
            )
            .order_by(DocumentJob.id.desc())
            .limit(1)
        )
        return await self._session.scalar(statement)

    async def pickup(self, *, worker_id: str, lease_seconds: int) -> DocumentJob | None:
        """Atomically claim one runnable Job without waiting on another Worker."""
        statement = (
            select(DocumentJob)
            .where(
                or_(
                    DocumentJob.status == "QUEUED",
                    and_(
                        DocumentJob.status == "RETRY_WAIT",
                        DocumentJob.next_retry_at <= func.now(),
                    ),
                )
            )
            .order_by(DocumentJob.created_at, DocumentJob.id)
            .with_for_update(skip_locked=True)
            .limit(1)
        )
        job = await self._session.scalar(statement)
        if job is None:
            return None

        database_now = await self._session.scalar(select(func.clock_timestamp()))
        if database_now is None:  # pragma: no cover - PostgreSQL always returns a timestamp
            raise RuntimeError("PostgreSQL did not return clock_timestamp()")

        job.status = "RUNNING"
        job.worker_id = worker_id
        job.attempt_no += 1
        job.next_retry_at = None
        job.lease_expires_at = database_now + datetime.timedelta(seconds=lease_seconds)
        job.updated_at = database_now
        await self._session.commit()
        await self._session.refresh(job)
        return job

    async def heartbeat(self, *, job_id: int, worker_id: str, lease_seconds: int) -> bool:
        """Extend a lease only while this Worker still owns the RUNNING Job."""
        statement = (
            update(DocumentJob)
            .where(
                DocumentJob.id == job_id,
                DocumentJob.status == "RUNNING",
                DocumentJob.worker_id == worker_id,
            )
            .values(
                lease_expires_at=func.clock_timestamp()
                + datetime.timedelta(seconds=lease_seconds),
                updated_at=func.clock_timestamp(),
            )
            .returning(DocumentJob.id)
        )
        renewed_job_id = await self._session.scalar(statement)
        await self._session.commit()
        return renewed_job_id is not None

    async def recover_expired(
        self,
        *,
        backoff_seconds: tuple[int, ...],
    ) -> RecoveryResult | None:
        """Recover one expired RUNNING Job without racing another sweeper."""
        statement = (
            select(DocumentJob)
            .where(
                DocumentJob.status == "RUNNING",
                DocumentJob.lease_expires_at < func.now(),
            )
            .order_by(DocumentJob.lease_expires_at, DocumentJob.id)
            .with_for_update(skip_locked=True)
            .limit(1)
        )
        job = await self._session.scalar(statement)
        if job is None:
            return None

        database_now = await self._session.scalar(select(func.clock_timestamp()))
        if database_now is None:  # pragma: no cover - PostgreSQL always returns a timestamp
            raise RuntimeError("PostgreSQL did not return clock_timestamp()")

        error_code = "PROCESSING_INTERRUPTED"
        error_message = "Worker lease expired before processing completed"
        job.worker_id = None
        job.lease_expires_at = None
        job.last_error_code = error_code
        job.last_error = error_message
        job.updated_at = database_now

        if job.attempt_no <= job.max_retries:
            backoff_index = job.attempt_no - 1
            if backoff_index < 0 or backoff_index >= len(backoff_seconds):
                raise ValueError(
                    "No retry backoff configured for expired Job "
                    f"attempt_no={job.attempt_no}, max_retries={job.max_retries}"
                )
            retry_delay = backoff_seconds[backoff_index]
            if retry_delay <= 0:
                raise ValueError(
                    f"Retry backoff must be positive, got {retry_delay} seconds"
                )
            job.status = "RETRY_WAIT"
            job.next_retry_at = database_now + datetime.timedelta(seconds=retry_delay)
            result = RecoveryResult(
                job_id=job.id,
                status=job.status,
                attempt_no=job.attempt_no,
                backoff_seconds=retry_delay,
            )
        else:
            job.status = "DEAD"
            job.next_retry_at = None
            job.finished_at = database_now
            job.callback_next_retry_at = database_now
            result = RecoveryResult(
                job_id=job.id,
                status=job.status,
                attempt_no=job.attempt_no,
                backoff_seconds=None,
            )

        await self._session.commit()
        return result

    async def enqueue(
        self,
        snapshot: ProcessDocumentRequest,
        *,
        max_retries: int,
    ) -> EnqueueResult:
        existing = await self.find_active(snapshot.document_id)
        if existing is not None:
            ensure_snapshot_matches(existing, snapshot)
            return EnqueueResult(job=existing, existing=True)

        values = snapshot.model_dump(mode="json")
        job = DocumentJob(
            **values,
            status="QUEUED",
            attempt_no=0,
            max_retries=max_retries,
            callback_attempt_no=0,
        )
        self._session.add(job)

        try:
            await self._session.commit()
        except IntegrityError:
            # A concurrent request may have won the partial unique-index race.
            # After rollback, READ COMMITTED can see that committed active Job.
            await self._session.rollback()
            existing = await self.find_active(snapshot.document_id)
            if existing is None:
                raise
            ensure_snapshot_matches(existing, snapshot)
            return EnqueueResult(job=existing, existing=True)

        await self._session.refresh(job)
        return EnqueueResult(job=job, existing=False)

