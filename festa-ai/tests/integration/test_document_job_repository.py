"""Verify idempotent Job enqueue behavior against a real disposable AI PostgreSQL DB."""

from __future__ import annotations

import asyncio
import datetime
import os

import pytest
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.schemas.documents import ProcessDocumentRequest
from app.db.models import DocumentJob
from app.repositories.document_job_repository import DocumentJobRepository
from app.services.document_snapshot_validator import SnapshotConflictError

TEST_DATABASE_URL = os.getenv("TEST_MIGRATION_DATABASE_URL")

pytestmark = [
    pytest.mark.asyncio,
    pytest.mark.skipif(
        not TEST_DATABASE_URL,
        reason="TEST_MIGRATION_DATABASE_URL not set — requires disposable AI PostgreSQL DB",
    ),
]


def _snapshot(**overrides: object) -> ProcessDocumentRequest:
    payload = {
        "documentId": 4201,
        "boothId": 10,
        "agentId": 7,
        "originalFilename": "guide.pdf",
        "contentType": "application/pdf",
        "fileSizeBytes": 1024,
        "storageProvider": "R2",
        "storageBucket": "festa-documents",
        "objectKey": "documents/4201/source.pdf",
        "sourceHash": "a" * 64,
    }
    return ProcessDocumentRequest.model_validate({**payload, **overrides})


@pytest.fixture
async def session_factory(migrated_engine):
    async_url = migrated_engine.url.render_as_string(hide_password=False)
    engine = create_async_engine(async_url)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    try:
        yield factory
    finally:
        async with engine.begin() as connection:
            await connection.run_sync(
                lambda sync_connection: sync_connection.execute(
                    DocumentJob.__table__.delete().where(
                        DocumentJob.document_id.in_((4201, 4202, 4203))
                    )
                )
            )
        await engine.dispose()


async def test_same_snapshot_returns_one_job_with_existing_true(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as first_session:
        first = await DocumentJobRepository(first_session).enqueue(_snapshot(), max_retries=3)
    async with session_factory() as second_session:
        second = await DocumentJobRepository(second_session).enqueue(_snapshot(), max_retries=3)

    assert first.existing is False
    assert second.existing is True
    assert second.job.id == first.job.id

    async with session_factory() as verification_session:
        count = await verification_session.scalar(
            select(func.count()).select_from(DocumentJob).where(DocumentJob.document_id == 4201)
        )
    assert count == 1


async def test_different_snapshot_returns_conflict_without_new_job(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as first_session:
        await DocumentJobRepository(first_session).enqueue(_snapshot(), max_retries=3)

    async with session_factory() as conflicting_session:
        with pytest.raises(SnapshotConflictError):
            await DocumentJobRepository(conflicting_session).enqueue(
                _snapshot(sourceHash="b" * 64), max_retries=3
            )

    async with session_factory() as verification_session:
        count = await verification_session.scalar(
            select(func.count()).select_from(DocumentJob).where(DocumentJob.document_id == 4201)
        )
    assert count == 1


async def test_concurrent_same_snapshot_creates_one_active_job(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    import asyncio

    async def enqueue_once():
        async with session_factory() as session:
            return await DocumentJobRepository(session).enqueue(_snapshot(), max_retries=3)

    first, second = await asyncio.gather(enqueue_once(), enqueue_once())

    assert first.job.id == second.job.id
    assert sorted([first.existing, second.existing]) == [False, True]


async def test_two_workers_never_pick_the_same_job(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    for document_id in (4201, 4202):
        async with session_factory() as session:
            await DocumentJobRepository(session).enqueue(
                _snapshot(documentId=document_id), max_retries=3
            )

    async def pick(worker_id: str):
        async with session_factory() as session:
            return await DocumentJobRepository(session).pickup(
                worker_id=worker_id, lease_seconds=90
            )

    first, second = await asyncio.gather(pick("worker-a"), pick("worker-b"))

    assert first is not None
    assert second is not None
    assert first.id != second.id
    assert {first.worker_id, second.worker_id} == {"worker-a", "worker-b"}
    assert first.status == second.status == "RUNNING"
    assert first.attempt_no == second.attempt_no == 1
    assert first.lease_expires_at is not None
    assert second.lease_expires_at is not None


async def test_only_one_worker_picks_a_single_job(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        await DocumentJobRepository(session).enqueue(_snapshot(), max_retries=3)

    async def pick(worker_id: str):
        async with session_factory() as session:
            return await DocumentJobRepository(session).pickup(
                worker_id=worker_id, lease_seconds=90
            )

    results = await asyncio.gather(pick("worker-a"), pick("worker-b"))

    assert sum(result is not None for result in results) == 1


async def test_heartbeat_extends_only_the_current_workers_lease(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        await DocumentJobRepository(session).enqueue(_snapshot(), max_retries=3)
    async with session_factory() as session:
        job = await DocumentJobRepository(session).pickup(
            worker_id="worker-a", lease_seconds=90
        )
    assert job is not None
    original_lease = job.lease_expires_at

    async with session_factory() as session:
        wrong_owner = await DocumentJobRepository(session).heartbeat(
            job_id=job.id, worker_id="worker-b", lease_seconds=180
        )
    async with session_factory() as session:
        current_owner = await DocumentJobRepository(session).heartbeat(
            job_id=job.id, worker_id="worker-a", lease_seconds=180
        )

    assert wrong_owner is False
    assert current_owner is True
    async with session_factory() as session:
        refreshed = await session.get(DocumentJob, job.id)
    assert refreshed is not None
    assert refreshed.lease_expires_at is not None
    assert original_lease is not None
    assert refreshed.lease_expires_at > original_lease


async def test_stopped_heartbeat_leaves_an_expired_running_job_for_recovery(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        await DocumentJobRepository(session).enqueue(_snapshot(), max_retries=3)
    async with session_factory() as session:
        job = await DocumentJobRepository(session).pickup(
            worker_id="worker-a", lease_seconds=90
        )
    assert job is not None

    expired_at = datetime.datetime.now(datetime.UTC) - datetime.timedelta(seconds=1)
    async with session_factory() as session:
        await session.execute(
            update(DocumentJob)
            .where(DocumentJob.id == job.id)
            .values(lease_expires_at=expired_at)
        )
        await session.commit()

    async with session_factory() as session:
        expired = await session.scalar(
            select(DocumentJob).where(
                DocumentJob.id == job.id,
                DocumentJob.status == "RUNNING",
                DocumentJob.lease_expires_at < func.now(),
            )
        )

    assert expired is not None
    assert expired.worker_id == "worker-a"


async def test_retry_wait_is_picked_only_after_next_retry_time(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with session_factory() as session:
        queued = await DocumentJobRepository(session).enqueue(_snapshot(), max_retries=3)

    future_retry = datetime.datetime.now(datetime.UTC) + datetime.timedelta(minutes=5)
    async with session_factory() as session:
        await session.execute(
            update(DocumentJob)
            .where(DocumentJob.id == queued.job.id)
            .values(status="RETRY_WAIT", next_retry_at=future_retry)
        )
        await session.commit()
    async with session_factory() as session:
        too_early = await DocumentJobRepository(session).pickup(
            worker_id="worker-a", lease_seconds=90
        )
    assert too_early is None

    due_retry = datetime.datetime.now(datetime.UTC) - datetime.timedelta(seconds=1)
    async with session_factory() as session:
        await session.execute(
            update(DocumentJob)
            .where(DocumentJob.id == queued.job.id)
            .values(next_retry_at=due_retry)
        )
        await session.commit()
    async with session_factory() as session:
        picked = await DocumentJobRepository(session).pickup(
            worker_id="worker-a", lease_seconds=90
        )

    assert picked is not None
    assert picked.id == queued.job.id
    assert picked.status == "RUNNING"
    assert picked.next_retry_at is None
