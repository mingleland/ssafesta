"""동일 검색 계약을 메모리 또는 임시 pgvector 테이블에서 실행한다."""

from __future__ import annotations

import math
from collections.abc import Sequence
from typing import Protocol

from .models import Chunk, SearchHit, TARGET_DIMENSION


class VectorStore(Protocol):
    def replace(self, **kwargs: object) -> None: ...

    def search(self, **kwargs: object) -> list[SearchHit]: ...

    def close(self) -> None: ...


class MemoryVectorStore:
    def __init__(self) -> None:
        self._rows: list[tuple[str, str, int, int, Chunk, list[float]]] = []

    def replace(self, **kwargs: object) -> None:
        run_id = str(kwargs["run_id"])
        model_id = str(kwargs["model_id"])
        booth_id = int(kwargs["booth_id"])
        agent_id = int(kwargs["agent_id"])
        chunks = list(kwargs["chunks"])  # type: ignore[arg-type]
        vectors = list(kwargs["vectors"])  # type: ignore[arg-type]
        if len(chunks) != len(vectors):
            raise ValueError("청크 수와 벡터 수가 다릅니다.")
        self._rows = [
            row for row in self._rows if row[:4] != (run_id, model_id, booth_id, agent_id)
        ]
        self._rows.extend(
            (run_id, model_id, booth_id, agent_id, chunk, list(vector))
            for chunk, vector in zip(chunks, vectors, strict=True)
        )

    def search(self, **kwargs: object) -> list[SearchHit]:
        run_id = str(kwargs["run_id"])
        model_id = str(kwargs["model_id"])
        booth_id = int(kwargs["booth_id"])
        agent_id = int(kwargs["agent_id"])
        query_vector = list(kwargs["query_vector"])  # type: ignore[arg-type]
        top_k = int(kwargs["top_k"])
        candidates = [
            row
            for row in self._rows
            if row[:4] == (run_id, model_id, booth_id, agent_id)
        ]
        ranked = sorted(candidates, key=lambda row: _cosine_distance(row[5], query_vector))[
            :top_k
        ]
        return [
            SearchHit(
                chunk_id=row[4].chunk_id,
                page=row[4].page,
                content=row[4].content,
                distance=_cosine_distance(row[5], query_vector),
                booth_id=row[2],
                agent_id=row[3],
            )
            for row in ranked
        ]

    def close(self) -> None:
        return None


class PgVectorStore:
    def __init__(self, database_url: str) -> None:
        try:
            import psycopg
        except ImportError as exc:  # pragma: no cover
            raise RuntimeError("psycopg가 필요합니다. Conda 환경을 먼저 생성하세요.") from exc
        self._connection = psycopg.connect(database_url)
        with self._connection.cursor() as cursor:
            cursor.execute("SELECT 1 FROM pg_extension WHERE extname = 'vector'")
            if cursor.fetchone() is None:
                raise RuntimeError("대상 PostgreSQL에 pgvector extension이 설치되어 있지 않습니다.")
            cursor.execute(
                f"""
                CREATE TEMP TABLE rag_spike_chunks (
                    run_id text NOT NULL,
                    model_id text NOT NULL,
                    booth_id bigint NOT NULL,
                    agent_id bigint NOT NULL,
                    chunk_id text NOT NULL,
                    page integer NOT NULL,
                    content text NOT NULL,
                    embedding vector({TARGET_DIMENSION}) NOT NULL
                ) ON COMMIT PRESERVE ROWS
                """
            )
        self._connection.commit()

    def replace(self, **kwargs: object) -> None:
        chunks = list(kwargs["chunks"])  # type: ignore[arg-type]
        vectors = list(kwargs["vectors"])  # type: ignore[arg-type]
        if len(chunks) != len(vectors):
            raise ValueError("청크 수와 벡터 수가 다릅니다.")
        keys = (
            str(kwargs["run_id"]),
            str(kwargs["model_id"]),
            int(kwargs["booth_id"]),
            int(kwargs["agent_id"]),
        )
        with self._connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM rag_spike_chunks WHERE run_id=%s AND model_id=%s "
                "AND booth_id=%s AND agent_id=%s",
                keys,
            )
            cursor.executemany(
                """
                INSERT INTO rag_spike_chunks
                    (run_id, model_id, booth_id, agent_id, chunk_id, page, content, embedding)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s::vector)
                """,
                [
                    (*keys, chunk.chunk_id, chunk.page, chunk.content, _vector_literal(vector))
                    for chunk, vector in zip(chunks, vectors, strict=True)
                ],
            )
        self._connection.commit()

    def search(self, **kwargs: object) -> list[SearchHit]:
        query_vector = _vector_literal(kwargs["query_vector"])  # type: ignore[arg-type]
        with self._connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT chunk_id, page, content, embedding <=> %s::vector AS distance,
                       booth_id, agent_id
                  FROM rag_spike_chunks
                 WHERE run_id=%s AND model_id=%s AND booth_id=%s AND agent_id=%s
                 ORDER BY embedding <=> %s::vector
                 LIMIT %s
                """,
                (
                    query_vector,
                    str(kwargs["run_id"]),
                    str(kwargs["model_id"]),
                    int(kwargs["booth_id"]),
                    int(kwargs["agent_id"]),
                    query_vector,
                    int(kwargs["top_k"]),
                ),
            )
            return [SearchHit(*row) for row in cursor.fetchall()]

    def close(self) -> None:
        self._connection.close()


def _vector_literal(vector: Sequence[float]) -> str:
    if len(vector) != TARGET_DIMENSION:
        raise ValueError(f"벡터 차원이 {TARGET_DIMENSION}이 아닙니다: {len(vector)}")
    return "[" + ",".join(format(float(value), ".9g") for value in vector) + "]"


def _cosine_distance(left: Sequence[float], right: Sequence[float]) -> float:
    if len(left) != len(right):
        raise ValueError("코사인 거리 계산 벡터의 차원이 다릅니다.")
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        return 1.0
    return 1.0 - dot / (left_norm * right_norm)
