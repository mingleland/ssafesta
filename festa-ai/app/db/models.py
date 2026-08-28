"""AI DB ORM models: `document_jobs`, `document_chunks` (spec 007 data-model.md).

`document_id` on both tables is a logical reference into Spring's Business DB
`ai_documents` table. It is intentionally a plain BIGINT with no ForeignKey
and no ON DELETE CASCADE (S15P21A604-262): the two tables live in different
PostgreSQL databases, so a real FK is not possible, and ownership boundary
requires FastAPI to never query Business DB directly. Consistency is
maintained by application-level snapshot validation and the Spring-driven
cleanup/reconciliation flow described in data-model.md, not by the database.
"""

from __future__ import annotations

import datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import TIMESTAMP
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base

# Terminal states are excluded from the active-job uniqueness window so a
# document can be reprocessed after a prior job finishes.
JOB_STATUSES = ("QUEUED", "RUNNING", "RETRY_WAIT", "SUCCEEDED", "DEAD", "CANCELLED")
ACTIVE_JOB_STATUSES = ("QUEUED", "RUNNING", "RETRY_WAIT")
CALLBACK_TERMINAL_CODES = (
    "DOCUMENT_NOT_FOUND",
    "JOB_DOCUMENT_MISMATCH",
    "JOB_NOT_REGISTERED_RETRY_EXHAUSTED",
)
CALLBACK_PENDING_TERMINAL_STATUSES = ("SUCCEEDED", "DEAD", "CANCELLED")

EMBEDDING_DIMENSION = 1536


class DocumentJob(Base):
    """Processing job queue entry. External API responses serialize `id` as `job_{id}`."""

    __tablename__ = "document_jobs"
    __table_args__ = (
        CheckConstraint(f"status IN {JOB_STATUSES!r}", name="ck_document_jobs_status"),
        CheckConstraint("attempt_no >= 0", name="ck_document_jobs_attempt_no_nonneg"),
        CheckConstraint("max_retries >= 0", name="ck_document_jobs_max_retries_nonneg"),
        CheckConstraint(
            f"callback_terminal_code IS NULL OR callback_terminal_code IN {CALLBACK_TERMINAL_CODES!r}",
            name="ck_document_jobs_callback_terminal_code",
        ),
        Index(
            "ix_document_jobs_active_document",
            "document_id",
            unique=True,
            postgresql_where=text(f"status IN {ACTIVE_JOB_STATUSES!r}"),
        ),
        Index("ix_document_jobs_pickup", "status", "next_retry_at"),
        Index(
            "ix_document_jobs_lease",
            "lease_expires_at",
            postgresql_where=text("status = 'RUNNING'"),
        ),
        Index(
            "ix_document_jobs_callback",
            "callback_next_retry_at",
            postgresql_where=text(
                "callback_delivered_at IS NULL AND callback_terminated_at IS NULL "
                f"AND status IN {CALLBACK_PENDING_TERMINAL_STATUSES!r}"
            ),
        ),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)

    # Business DB `ai_documents.id` — logical reference only, no FK (see module docstring).
    document_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    booth_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    agent_id: Mapped[int] = mapped_column(BigInteger, nullable=False)

    source_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    original_filename: Mapped[str] = mapped_column(Text, nullable=False)
    content_type: Mapped[str] = mapped_column(String(100), nullable=False)
    file_size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    storage_provider: Mapped[str] = mapped_column(String(20), nullable=False)
    storage_bucket: Mapped[str] = mapped_column(Text, nullable=False)
    object_key: Mapped[str] = mapped_column(Text, nullable=False)

    status: Mapped[str] = mapped_column(String(20), nullable=False)

    attempt_no: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    max_retries: Mapped[int] = mapped_column(Integer, nullable=False, server_default="3")
    worker_id: Mapped[str | None] = mapped_column(String(100))
    lease_expires_at: Mapped[datetime.datetime | None] = mapped_column(TIMESTAMP(timezone=True))
    next_retry_at: Mapped[datetime.datetime | None] = mapped_column(TIMESTAMP(timezone=True))

    last_error_code: Mapped[str | None] = mapped_column(String(50))
    last_error: Mapped[str | None] = mapped_column(Text)
    chunk_count: Mapped[int | None] = mapped_column(Integer)

    callback_attempt_no: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    callback_next_retry_at: Mapped[datetime.datetime | None] = mapped_column(TIMESTAMP(timezone=True))
    callback_delivered_at: Mapped[datetime.datetime | None] = mapped_column(TIMESTAMP(timezone=True))
    callback_terminated_at: Mapped[datetime.datetime | None] = mapped_column(TIMESTAMP(timezone=True))
    callback_terminal_code: Mapped[str | None] = mapped_column(String(50))

    created_at: Mapped[datetime.datetime] = mapped_column(
        TIMESTAMP(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime.datetime] = mapped_column(
        TIMESTAMP(timezone=True), nullable=False, server_default=func.now()
    )
    finished_at: Mapped[datetime.datetime | None] = mapped_column(TIMESTAMP(timezone=True))


class DocumentChunk(Base):
    """Embedded document chunk. `searchable` flips true only after Spring accepts the READY callback."""

    __tablename__ = "document_chunks"
    __table_args__ = (
        UniqueConstraint("document_id", "chunk_no", name="uq_document_chunks_document_chunk_no"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)

    # Business DB `ai_documents.id` — logical reference only, no FK (see module docstring).
    document_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    booth_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    agent_id: Mapped[int] = mapped_column(BigInteger, nullable=False)

    chunk_no: Mapped[int] = mapped_column(Integer, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    embedding: Mapped[list[float]] = mapped_column(Vector(EMBEDDING_DIMENSION), nullable=False)
    embedding_model_id: Mapped[str] = mapped_column(Text, nullable=False)

    page_number: Mapped[int | None] = mapped_column(Integer)
    section: Mapped[str | None] = mapped_column(Text)

    searchable: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
