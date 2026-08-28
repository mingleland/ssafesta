"""Verify active Job snapshots are compared field-for-field without Business DB access."""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.api.schemas.documents import ProcessDocumentRequest
from app.services.document_snapshot_validator import (
    SnapshotConflictError,
    ensure_snapshot_matches,
)
from tests.unit.test_document_schemas import VALID_REQUEST


def _snapshot() -> ProcessDocumentRequest:
    return ProcessDocumentRequest.model_validate(VALID_REQUEST)


def _job(**overrides: object) -> SimpleNamespace:
    values = {
        "document_id": 42,
        "booth_id": 10,
        "agent_id": 7,
        "original_filename": "guide.pdf",
        "content_type": "application/pdf",
        "file_size_bytes": 1024,
        "storage_provider": "R2",
        "storage_bucket": "festa-documents",
        "object_key": "documents/42/source.pdf",
        "source_hash": "a" * 64,
    }
    return SimpleNamespace(**{**values, **overrides})


def test_matching_snapshot_is_accepted() -> None:
    ensure_snapshot_matches(_job(), _snapshot())


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("booth_id", 11),
        ("agent_id", 8),
        ("original_filename", "other.pdf"),
        ("content_type", "text/plain"),
        ("file_size_bytes", 2048),
        ("storage_provider", "MINIO_LOCAL"),
        ("storage_bucket", "other-bucket"),
        ("object_key", "documents/42/other.pdf"),
        ("source_hash", "b" * 64),
    ],
)
def test_any_snapshot_difference_is_a_conflict(field: str, value: object) -> None:
    with pytest.raises(SnapshotConflictError):
        ensure_snapshot_matches(_job(**{field: value}), _snapshot())

