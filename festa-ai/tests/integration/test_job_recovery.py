"""Verify expired Worker leases are recovered with finite retry backoff."""

from __future__ import annotations

import asyncio
import datetime
import os

import pytest
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.schemas.documents import ProcessDocumentRequest
from app.db.models import DocumentJob
from app.repositories.document_job_repository import DocumentJobRepository

TEST_DATABASE_URL = os.getenv("TEST_MIGRATION_DATABASE_URL")
DOCUMENT_IDS = (4301, 4302, 4303, 4304)
BACKOFF_SECONDS = (60, 300, 900)

pytestmark = [
    pytest.mark.asyncio,
    pytest.mark.failure_injection,
    pytest.mark.skipif(
        not TEST_DATABASE_URL,
        reason="TEST_MIGRATION_DATABASE_URL not set — requires disposable AI PostgreSQL DB",
    ),
]


def _snapshot(document_id: int) -> ProcessDocumentRequest:
    return ProcessDocumentRequest.model_validate(
        {
            "documentId": document_id,
            "boothId": 10,
            "agentId": 7,
            "originalFilename": "guide.pdf",
            "contentType": "application/pdf",
            "fileSizeBytes": 1024,
            "storageProvider": "R2",
            "storageBucket": "festa-documents",
            "objectKey": f"documents/{document_id}/source.pdf",
            "sourceHash": "a" * 64,
        }
    )


@pytest.fixture
async def session_factory(migrated_engine):
    async_url = migrated_engine.url.render_as_string(hide_password=False)
    engine = create_async_engine(async_url)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    try:
        yield factory
    finally:
        async with engine.begin() as connection:
            await connection.execute(
                DocumentJob.__table__.delete().where(
                    DocumentJob.document_id.in_(DOCUMENT_IDS)
                )
            )
        await engine.dispose()


async def _enqueue_and_pick(
    session_factory: async_sessionmaker[AsyncSession],
    *,
    document_id: int,
    worker_id: str,
) -> DocumentJob:
    async with session_factory() as session:
        await DocumentJobRepository(session).enqueue(
            _snapshot(document_id), max_retries=3
        )
    async with session_factory() as session:
        job = await DocumentJobRepository(session).pickup(
            worker_id=worker_id, lease_seconds=90
        )
    assert job is not None
    return job


async def _expire(
    session_factory: async_sessionmaker[AsyncSession], job_id: int
) -> None:
    async with session_factory() as session:
        await session.execute(
            update(DocumentJob)
            .where(DocumentJob.id == job_id)
            .values(
                lease_expires_at=datetime.datetime.now(datetime.UTC)
                - datetime.timedelta(seconds=1)
            )
        )
        await session.commit()


async def _make_retry_due(
    session_factory: async_sessionmaker[AsyncSession], job_id: int
) -> None:
    async with session_factory() as session:
        await session.execute(
            update(DocumentJob)
            .where(DocumentJob.id == job_id)
            .values(
                next_retry_at=datetime.datetime.now(datetime.UTC)
                - datetime.timedelta(seconds=1)
            )
        )
        await session.commit()


async def test_worker_kill_is_recovered_and_can_be_picked_by_another_worker(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    job = await _enqueue_and_pick(
        session_factory, document_id=4301, worker_id="worker-a"
    )
    await _expire(session_factory, job.id)

    async with session_factory() as session:
        recovered = await DocumentJobRepository(session).recover_expired(
            backoff_seconds=BACKOFF_SECONDS
        )

    assert recovered is not None
    assert recovered.job_id == job.id
    assert recovered.status == "RETRY_WAIT"
    assert recovered.backoff_seconds == 60

    async with session_factory() as session:
        stored = await session.get(DocumentJob, job.id)
    assert stored is not None
    assert stored.status == "RETRY_WAIT"
    assert stored.worker_id is None
    assert stored.lease_expires_at is None
    assert stored.next_retry_at is not None
    assert stored.last_error_code == "PROCESSING_INTERRUPTED"
    assert stored.last_error == "Worker lease expired before processing completed"

    await _make_retry_due(session_factory, job.id)
    async with session_factory() as session:
        retried = await DocumentJobRepository(session).pickup(
            worker_id="worker-b", lease_seconds=90
        )
    assert retried is not None
    assert retried.id == job.id
    assert retried.worker_id == "worker-b"
    assert retried.attempt_no == 2


async def test_backoff_sequence_ends_in_dead_after_three_retries(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    job = await _enqueue_and_pick(
        session_factory, document_id=4302, worker_id="worker-1"
    )

    for attempt_no, expected_backoff in enumerate(BACKOFF_SECONDS, start=1):
        await _expire(session_factory, job.id)
        async with session_factory() as session:
            recovered = await DocumentJobRepository(session).recover_expired(
                backoff_seconds=BACKOFF_SECONDS
            )
        assert recovered is not None
        assert recovered.status == "RETRY_WAIT"
        assert recovered.attempt_no == attempt_no
        assert recovered.backoff_seconds == expected_backoff

        await _make_retry_due(session_factory, job.id)
        async with session_factory() as session:
            job = await DocumentJobRepository(session).pickup(
                worker_id=f"worker-{attempt_no + 1}", lease_seconds=90
            )
        assert job is not None
        assert job.attempt_no == attempt_no + 1

    await _expire(session_factory, job.id)
    async with session_factory() as session:
        terminal = await DocumentJobRepository(session).recover_expired(
            backoff_seconds=BACKOFF_SECONDS
        )

    assert terminal is not None
    assert terminal.status == "DEAD"
    assert terminal.attempt_no == 4
    assert terminal.backoff_seconds is None

    async with session_factory() as session:
        stored = await session.get(DocumentJob, job.id)
    assert stored is not None
    assert stored.status == "DEAD"
    assert stored.worker_id is None
    assert stored.lease_expires_at is None
    assert stored.next_retry_at is None
    assert stored.last_error_code == "PROCESSING_INTERRUPTED"
    assert stored.finished_at is not None
    assert stored.callback_next_retry_at is not None


async def test_concurrent_sweepers_recover_an_expired_job_once(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    job = await _enqueue_and_pick(
        session_factory, document_id=4303, worker_id="worker-a"
    )
    await _expire(session_factory, job.id)

    async def recover_once():
        async with session_factory() as session:
            return await DocumentJobRepository(session).recover_expired(
                backoff_seconds=BACKOFF_SECONDS
            )

    first, second = await asyncio.gather(recover_once(), recover_once())

    recovered = [result for result in (first, second) if result is not None]
    assert len(recovered) == 1
    assert recovered[0].job_id == job.id


async def test_sweeper_does_not_recover_a_live_lease(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    job = await _enqueue_and_pick(
        session_factory, document_id=4304, worker_id="worker-live"
    )

    async with session_factory() as session:
        recovered = await DocumentJobRepository(session).recover_expired(
            backoff_seconds=BACKOFF_SECONDS
        )

    assert recovered is None
    async with session_factory() as session:
        stored = await session.get(DocumentJob, job.id)
    assert stored is not None
    assert stored.status == "RUNNING"
    assert stored.worker_id == "worker-live"
