"""Exercise the real FastAPI intake endpoint through to a disposable AI DB."""

from __future__ import annotations

import asyncio
import os
from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.errors import (
    ApiError,
    api_error_handler,
    request_validation_error_handler,
)
from app.api.v1.documents import router
from app.db.models import DocumentJob

TEST_DATABASE_URL = os.getenv("TEST_MIGRATION_DATABASE_URL")
DOCUMENT_IDS = (4301, 4302)
AUTH_HEADERS = {"Authorization": "Bearer spring-token"}

pytestmark = [
    pytest.mark.asyncio,
    pytest.mark.skipif(
        not TEST_DATABASE_URL,
        reason="TEST_MIGRATION_DATABASE_URL not set — requires disposable AI PostgreSQL DB",
    ),
]


def _payload(document_id: int = 4301, **overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "documentId": document_id,
        "boothId": 10,
        "agentId": 7,
        "originalFilename": "guide.pdf",
        "contentType": "application/pdf",
        "fileSizeBytes": 20_971_520,
        "storageProvider": "R2",
        "storageBucket": "festa-documents",
        "objectKey": f"documents/{document_id}/source.pdf",
        "sourceHash": "a" * 64,
    }
    return {**payload, **overrides}


@pytest.fixture
async def e2e_context(migrated_engine):
    async_url = migrated_engine.url.render_as_string(hide_password=False)
    engine = create_async_engine(async_url)
    factory = async_sessionmaker(engine, expire_on_commit=False)

    app = FastAPI()
    app.state.settings = SimpleNamespace(
        internal_spring_to_ai_tokens=["spring-token"],
        job_max_retries=3,
    )
    app.state.ai_db_session_factory = factory
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, request_validation_error_handler)
    app.include_router(router, prefix="/ai/v1")

    try:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            yield client, factory
    finally:
        async with engine.begin() as connection:
            await connection.run_sync(
                lambda sync_connection: sync_connection.execute(
                    DocumentJob.__table__.delete().where(
                        DocumentJob.document_id.in_(DOCUMENT_IDS)
                    )
                )
            )
        await engine.dispose()


async def test_http_intake_is_idempotent_and_conflicts_on_changed_snapshot(
    e2e_context: tuple[httpx.AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, factory = e2e_context

    assert (await client.post("/ai/v1/documents/process", json=_payload())).status_code == 401
    invalid = await client.post(
        "/ai/v1/documents/process",
        json=_payload(fileSizeBytes=0),
        headers=AUTH_HEADERS,
    )
    assert invalid.status_code == 422

    first = await client.post(
        "/ai/v1/documents/process", json=_payload(), headers=AUTH_HEADERS
    )
    second = await client.post(
        "/ai/v1/documents/process", json=_payload(), headers=AUTH_HEADERS
    )
    conflict = await client.post(
        "/ai/v1/documents/process",
        json=_payload(sourceHash="b" * 64),
        headers=AUTH_HEADERS,
    )

    assert first.status_code == second.status_code == 202
    assert first.json()["existing"] is False
    assert second.json()["existing"] is True
    assert first.json()["jobId"] == second.json()["jobId"]
    assert conflict.status_code == 409

    async with factory() as session:
        count = await session.scalar(
            select(func.count()).select_from(DocumentJob).where(
                DocumentJob.document_id == 4301
            )
        )
    assert count == 1


async def test_concurrent_http_requests_create_one_active_job(
    e2e_context: tuple[httpx.AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, factory = e2e_context
    requests = [
        client.post(
            "/ai/v1/documents/process",
            json=_payload(document_id=4302),
            headers=AUTH_HEADERS,
        )
        for _ in range(2)
    ]

    first, second = await asyncio.gather(*requests)

    assert first.status_code == second.status_code == 202
    assert sorted([first.json()["existing"], second.json()["existing"]]) == [
        False,
        True,
    ]
    assert first.json()["jobId"] == second.json()["jobId"]
    async with factory() as session:
        count = await session.scalar(
            select(func.count()).select_from(DocumentJob).where(
                DocumentJob.document_id == 4302
            )
        )
    assert count == 1
