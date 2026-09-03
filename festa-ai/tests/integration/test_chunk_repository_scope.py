"""Prove real pgvector retrieval cannot cross Booth, Agent, or READY boundaries."""

from __future__ import annotations

import os

import pytest
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.db.models import DocumentChunk
from app.repositories.chunk_repository import ChunkRepository, ChunkScope

TEST_DATABASE_URL = os.getenv("TEST_MIGRATION_DATABASE_URL")

pytestmark = [
    pytest.mark.asyncio,
    pytest.mark.isolation,
    pytest.mark.skipif(
        not TEST_DATABASE_URL,
        reason="TEST_MIGRATION_DATABASE_URL not set — requires disposable AI PostgreSQL DB",
    ),
]


async def test_vector_search_returns_only_matching_scope_and_searchable_chunks(
    migrated_engine,
) -> None:
    async_url = migrated_engine.url.render_as_string(hide_password=False)
    engine = create_async_engine(async_url)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    document_ids = (91001, 91002, 91003, 91004)
    vector = [0.01] * 1536

    try:
        async with factory() as session:
            session.add_all(
                [
                    DocumentChunk(
                        document_id=91001,
                        booth_id=10,
                        agent_id=20,
                        chunk_no=0,
                        content="허용된 문서",
                        embedding=vector,
                        embedding_model_id="test-embedding",
                        searchable=True,
                    ),
                    DocumentChunk(
                        document_id=91002,
                        booth_id=11,
                        agent_id=20,
                        chunk_no=0,
                        content="다른 부스 문서",
                        embedding=vector,
                        embedding_model_id="test-embedding",
                        searchable=True,
                    ),
                    DocumentChunk(
                        document_id=91003,
                        booth_id=10,
                        agent_id=21,
                        chunk_no=0,
                        content="다른 직원 문서",
                        embedding=vector,
                        embedding_model_id="test-embedding",
                        searchable=True,
                    ),
                    DocumentChunk(
                        document_id=91004,
                        booth_id=10,
                        agent_id=20,
                        chunk_no=0,
                        content="아직 READY가 아닌 문서",
                        embedding=vector,
                        embedding_model_id="test-embedding",
                        searchable=False,
                    ),
                ]
            )
            await session.commit()

        async with factory() as session:
            chunks = await ChunkRepository(session).search_ready_chunks(
                scope=ChunkScope(booth_id=10, agent_id=20),
                query_embedding=tuple(vector),
                top_k=10,
            )

        assert [chunk.document_id for chunk in chunks] == [91001]
        assert [chunk.content for chunk in chunks] == ["허용된 문서"]
    finally:
        async with factory() as session:
            await session.execute(
                delete(DocumentChunk).where(DocumentChunk.document_id.in_(document_ids))
            )
            await session.commit()
        await engine.dispose()
