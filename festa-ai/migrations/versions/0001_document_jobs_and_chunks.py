"""Create document_jobs and document_chunks (AI DB, spec 007 data-model.md).

Revision ID: 0001_document_jobs_and_chunks
Revises:
Create Date: 2026-08-27

`document_id` on both tables is a logical reference into Spring's Business
DB `ai_documents` — no ForeignKey, no ON DELETE CASCADE (S15P21A604-262:
Business DB and AI DB are separate PostgreSQL databases, so a real FK
cannot exist). This migration assumes Infra has already created the AI
database and installed the `vector` extension on it; it does not attempt
either (least-privilege migration role, per plan.md Section 9).
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from pgvector.sqlalchemy import Vector

revision: str = "0001_document_jobs_and_chunks"
down_revision: str | None = None
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

JOB_STATUSES = ("QUEUED", "RUNNING", "RETRY_WAIT", "SUCCEEDED", "DEAD", "CANCELLED")
ACTIVE_JOB_STATUSES = ("QUEUED", "RUNNING", "RETRY_WAIT")
CALLBACK_PENDING_TERMINAL_STATUSES = ("SUCCEEDED", "DEAD", "CANCELLED")
CALLBACK_TERMINAL_CODES = (
    "DOCUMENT_NOT_FOUND",
    "JOB_DOCUMENT_MISMATCH",
    "JOB_NOT_REGISTERED_RETRY_EXHAUSTED",
)
EMBEDDING_DIMENSION = 1536


def upgrade() -> None:
    op.create_table(
        "document_jobs",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("document_id", sa.BigInteger(), nullable=False),
        sa.Column("booth_id", sa.BigInteger(), nullable=False),
        sa.Column("agent_id", sa.BigInteger(), nullable=False),
        sa.Column("source_hash", sa.String(64), nullable=False),
        sa.Column("original_filename", sa.Text(), nullable=False),
        sa.Column("content_type", sa.String(100), nullable=False),
        sa.Column("file_size_bytes", sa.BigInteger(), nullable=False),
        sa.Column("storage_provider", sa.String(20), nullable=False),
        sa.Column("storage_bucket", sa.Text(), nullable=False),
        sa.Column("object_key", sa.Text(), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("attempt_no", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("max_retries", sa.Integer(), nullable=False, server_default="3"),
        sa.Column("worker_id", sa.String(100), nullable=True),
        sa.Column("lease_expires_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column("next_retry_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column("last_error_code", sa.String(50), nullable=True),
        sa.Column("last_error", sa.Text(), nullable=True),
        sa.Column("chunk_count", sa.Integer(), nullable=True),
        sa.Column("callback_attempt_no", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("callback_next_retry_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column("callback_delivered_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column("callback_terminated_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column("callback_terminal_code", sa.String(50), nullable=True),
        sa.Column(
            "created_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.TIMESTAMP(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column("finished_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.CheckConstraint(f"status IN {JOB_STATUSES!r}", name="ck_document_jobs_status"),
        sa.CheckConstraint("attempt_no >= 0", name="ck_document_jobs_attempt_no_nonneg"),
        sa.CheckConstraint("max_retries >= 0", name="ck_document_jobs_max_retries_nonneg"),
        sa.CheckConstraint(
            f"callback_terminal_code IS NULL OR callback_terminal_code IN {CALLBACK_TERMINAL_CODES!r}",
            name="ck_document_jobs_callback_terminal_code",
        ),
    )

    # Exactly one active job per document — a document that is not
    # QUEUED/RUNNING/RETRY_WAIT may be reprocessed by a fresh job.
    op.create_index(
        "ix_document_jobs_active_document",
        "document_jobs",
        ["document_id"],
        unique=True,
        postgresql_where=sa.text(f"status IN {ACTIVE_JOB_STATUSES!r}"),
    )
    op.create_index(
        "ix_document_jobs_pickup",
        "document_jobs",
        ["status", "next_retry_at"],
    )
    op.create_index(
        "ix_document_jobs_lease",
        "document_jobs",
        ["lease_expires_at"],
        postgresql_where=sa.text("status = 'RUNNING'"),
    )
    op.create_index(
        "ix_document_jobs_callback",
        "document_jobs",
        ["callback_next_retry_at"],
        postgresql_where=sa.text(
            "callback_delivered_at IS NULL AND callback_terminated_at IS NULL "
            f"AND status IN {CALLBACK_PENDING_TERMINAL_STATUSES!r}"
        ),
    )

    op.create_table(
        "document_chunks",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("document_id", sa.BigInteger(), nullable=False),
        sa.Column("booth_id", sa.BigInteger(), nullable=False),
        sa.Column("agent_id", sa.BigInteger(), nullable=False),
        sa.Column("chunk_no", sa.Integer(), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("embedding", Vector(EMBEDDING_DIMENSION), nullable=False),
        sa.Column("embedding_model_id", sa.Text(), nullable=False),
        sa.Column("page_number", sa.Integer(), nullable=True),
        sa.Column("section", sa.Text(), nullable=True),
        sa.Column("searchable", sa.Boolean(), nullable=False, server_default="false"),
        sa.UniqueConstraint(
            "document_id", "chunk_no", name="uq_document_chunks_document_chunk_no"
        ),
    )


def downgrade() -> None:
    op.drop_table("document_chunks")
    op.drop_index("ix_document_jobs_callback", table_name="document_jobs")
    op.drop_index("ix_document_jobs_lease", table_name="document_jobs")
    op.drop_index("ix_document_jobs_pickup", table_name="document_jobs")
    op.drop_index("ix_document_jobs_active_document", table_name="document_jobs")
    op.drop_table("document_jobs")
