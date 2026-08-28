"""Verify idempotent Job enqueue behavior against a real disposable AI PostgreSQL DB."""

from __future__ import annotations

import os

import pytest
from sqlalchemy import func, select
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
                    DocumentJob.__table__.delete().where(DocumentJob.document_id == 4201)
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
