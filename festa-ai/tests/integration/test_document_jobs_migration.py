"""Integration tests for the document_jobs / document_chunks migration.

Covers S15P21A604-95 completion criteria: `alembic upgrade head` succeeds
against a real local PostgreSQL, and a duplicate active Job insert for the
same document is rejected by the database (not just application code).
"""

from __future__ import annotations

import datetime

import pytest
import sqlalchemy as sa
from sqlalchemy.exc import IntegrityError

JOB_COLUMNS = {
    "document_id": 1,
    "booth_id": 1,
    "agent_id": 1,
    "source_hash": "a" * 64,
    "original_filename": "spec.pdf",
    "content_type": "application/pdf",
    "file_size_bytes": 1024,
    "storage_provider": "R2",
    "storage_bucket": "festa-documents",
    "object_key": "documents/1/spec.pdf",
    "status": "QUEUED",
}


def _insert_job(connection: sa.Connection, **overrides: object) -> None:
    values = {**JOB_COLUMNS, **overrides}
    columns = ", ".join(values)
    placeholders = ", ".join(f":{key}" for key in values)
    connection.execute(
        sa.text(f"INSERT INTO document_jobs ({columns}) VALUES ({placeholders})"),
        values,
    )


def test_active_job_uniqueness_rejects_duplicate(db_connection: sa.Connection) -> None:
    _insert_job(db_connection, document_id=101, status="QUEUED")

    with pytest.raises(IntegrityError, match="ix_document_jobs_active_document"):
        _insert_job(db_connection, document_id=101, status="RUNNING")


def test_active_job_uniqueness_allows_reprocess_after_terminal_status(
    db_connection: sa.Connection,
) -> None:
    _insert_job(db_connection, document_id=102, status="SUCCEEDED")

    # A prior job for the same document reaching a terminal state must not
    # block a fresh job — this is the whole point of the *partial* index.
    _insert_job(db_connection, document_id=102, status="QUEUED")


def test_document_chunks_unique_document_and_chunk_no(db_connection: sa.Connection) -> None:
    embedding = "[" + ",".join("0" for _ in range(1536)) + "]"
    insert = sa.text(
        "INSERT INTO document_chunks "
        "(document_id, booth_id, agent_id, chunk_no, content, embedding, embedding_model_id) "
        "VALUES (:document_id, 1, 1, :chunk_no, 'text', :embedding, 'test-model')"
    )
    db_connection.execute(
        insert, {"document_id": 201, "chunk_no": 0, "embedding": embedding}
    )

    with pytest.raises(IntegrityError, match="uq_document_chunks_document_chunk_no"):
        db_connection.execute(
            insert, {"document_id": 201, "chunk_no": 0, "embedding": embedding}
        )


def test_document_id_has_no_foreign_key_to_business_db(db_connection: sa.Connection) -> None:
    inspector = sa.inspect(db_connection)
    for table in ("document_jobs", "document_chunks"):
        assert inspector.get_foreign_keys(table) == [], (
            f"{table}.document_id must stay a logical reference with no FK "
            "(S15P21A604-262: Business DB and AI DB are separate databases)"
        )


def test_new_job_defaults_match_spec(db_connection: sa.Connection) -> None:
    _insert_job(db_connection, document_id=301)
    row = db_connection.execute(
        sa.text(
            "SELECT attempt_no, max_retries, callback_attempt_no, created_at, updated_at "
            "FROM document_jobs WHERE document_id = 301"
        )
    ).one()

    assert row.attempt_no == 0
    assert row.max_retries == 3
    assert row.callback_attempt_no == 0
    assert isinstance(row.created_at, datetime.datetime)
    assert isinstance(row.updated_at, datetime.datetime)
