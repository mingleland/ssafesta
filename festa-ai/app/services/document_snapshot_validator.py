"""Compare Spring-verified document snapshots stored in active AI Jobs."""

from __future__ import annotations

from typing import Protocol

from app.api.schemas.documents import ProcessDocumentRequest

SNAPSHOT_FIELDS = (
    "document_id",
    "booth_id",
    "agent_id",
    "original_filename",
    "content_type",
    "file_size_bytes",
    "storage_provider",
    "storage_bucket",
    "object_key",
    "source_hash",
)


class StoredDocumentSnapshot(Protocol):
    document_id: int
    booth_id: int
    agent_id: int
    original_filename: str
    content_type: str
    file_size_bytes: int
    storage_provider: str
    storage_bucket: str
    object_key: str
    source_hash: str


class SnapshotConflictError(Exception):
    """An active Job exists for the document with a different snapshot."""


def ensure_snapshot_matches(
    stored: StoredDocumentSnapshot, requested: ProcessDocumentRequest
) -> None:
    requested_values = requested.model_dump(mode="json")
    if any(
        getattr(stored, field) != requested_values[field]
        for field in SNAPSHOT_FIELDS
    ):
        raise SnapshotConflictError()

