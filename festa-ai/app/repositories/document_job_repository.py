"""Persist and idempotently reuse document-processing Jobs in the AI DB."""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.schemas.documents import ProcessDocumentRequest
from app.db.models import ACTIVE_JOB_STATUSES, DocumentJob
from app.services.document_snapshot_validator import ensure_snapshot_matches


@dataclass(frozen=True, slots=True)
class EnqueueResult:
    job: DocumentJob
    existing: bool


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

