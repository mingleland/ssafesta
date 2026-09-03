"""Verify vector retrieval cannot omit the server-owned Booth and Agent scope."""

from __future__ import annotations

from types import SimpleNamespace

import pytest
from sqlalchemy.dialects import postgresql

from app.repositories.chunk_repository import ChunkRepository, ChunkScope


class _Rows:
    def __init__(self, rows: list[SimpleNamespace]) -> None:
        self._rows = rows

    def all(self) -> list[SimpleNamespace]:
        return self._rows


class _Session:
    def __init__(self, rows: list[SimpleNamespace] | None = None) -> None:
        self.statement = None
        self._rows = rows or []

    async def execute(self, statement):
        self.statement = statement
        return _Rows(self._rows)


def _embedding() -> tuple[float, ...]:
    return (1.0,) + (0.0,) * 1535


@pytest.mark.asyncio
async def test_search_statement_always_filters_booth_agent_and_searchable() -> None:
    session = _Session()
    repository = ChunkRepository(session)  # type: ignore[arg-type]

    await repository.search_ready_chunks(
        scope=ChunkScope(booth_id=101, agent_id=202),
        query_embedding=_embedding(),
        top_k=5,
    )

    assert session.statement is not None
    compiled = session.statement.compile(dialect=postgresql.dialect())
    sql = str(compiled)
    assert "document_chunks.booth_id =" in sql
    assert "document_chunks.agent_id =" in sql
    assert "document_chunks.searchable IS true" in sql
    assert 101 in compiled.params.values()
    assert 202 in compiled.params.values()


@pytest.mark.asyncio
async def test_returned_chunks_preserve_scope_and_source_metadata() -> None:
    session = _Session(
        [
            SimpleNamespace(
                id=1,
                document_id=77,
                booth_id=101,
                agent_id=202,
                chunk_no=3,
                content="행사 운영 시간은 오전 10시부터입니다.",
                embedding_model_id="embedding-v1",
                page_number=4,
                section="운영 시간",
                distance=0.125,
            )
        ]
    )
    repository = ChunkRepository(session)  # type: ignore[arg-type]

    result = await repository.search_ready_chunks(
        scope=ChunkScope(booth_id=101, agent_id=202),
        query_embedding=_embedding(),
        top_k=1,
    )

    assert len(result) == 1
    assert result[0].booth_id == 101
    assert result[0].agent_id == 202
    assert result[0].document_id == 77
    assert result[0].page_number == 4
    assert result[0].section == "운영 시간"


@pytest.mark.asyncio
async def test_repository_fails_closed_if_database_returns_wrong_scope() -> None:
    session = _Session(
        [
            SimpleNamespace(
                id=1,
                document_id=77,
                booth_id=999,
                agent_id=202,
                chunk_no=0,
                content="다른 부스의 내용",
                embedding_model_id="embedding-v1",
                page_number=None,
                section=None,
                distance=0.1,
            )
        ]
    )
    repository = ChunkRepository(session)  # type: ignore[arg-type]

    with pytest.raises(RuntimeError, match="RAG_SCOPE_MISMATCH"):
        await repository.search_ready_chunks(
            scope=ChunkScope(booth_id=101, agent_id=202),
            query_embedding=_embedding(),
            top_k=1,
        )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("embedding", "top_k"),
    [
        ((0.0,) * 1535, 5),
        ((float("nan"),) + (0.0,) * 1535, 5),
        ((0.0,) * 1536, 5),
        (_embedding(), 0),
        (_embedding(), 1.5),
    ],
)
async def test_invalid_search_input_never_reaches_database(
    embedding: tuple[float, ...], top_k: int | float
) -> None:
    session = _Session()
    repository = ChunkRepository(session)  # type: ignore[arg-type]

    with pytest.raises(ValueError):
        await repository.search_ready_chunks(
            scope=ChunkScope(booth_id=101, agent_id=202),
            query_embedding=embedding,
            top_k=top_k,  # type: ignore[arg-type]
        )

    assert session.statement is None
