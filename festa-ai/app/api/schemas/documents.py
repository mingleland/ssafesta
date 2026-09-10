"""Document-processing API payloads that implement the spec 007 OpenAPI contract."""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    """Use the contract's camelCase JSON names and reject undeclared fields."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class DocumentContentType(str, Enum):
    PDF = "application/pdf"
    MARKDOWN = "text/markdown"
    TEXT = "text/plain"


class StorageProvider(str, Enum):
    R2 = "R2"
    MINIO_LOCAL = "MINIO_LOCAL"


class ProcessDocumentRequest(ApiModel):
    job_id: int = Field(ge=1, json_schema_extra={"format": "int64"})
    attempt_no: int = Field(ge=0)
    document_id: int = Field(ge=1, json_schema_extra={"format": "int64"})
    booth_id: int = Field(ge=1, json_schema_extra={"format": "int64"})
    agent_id: int = Field(ge=1, json_schema_extra={"format": "int64"})
    original_filename: str = Field(min_length=1, max_length=255)
    content_type: DocumentContentType
    file_size_bytes: int = Field(ge=1, le=20_971_520)
    storage_provider: StorageProvider
    storage_bucket: str = Field(min_length=1)
    object_key: str = Field(min_length=1)
    source_hash: str = Field(pattern=r"^[a-f0-9]{64}$")


class CancelDocumentProcessingRequest(ApiModel):
    job_id: int = Field(ge=1, json_schema_extra={"format": "int64"})
    attempt_no: int = Field(ge=0)


class ErrorResponse(ApiModel):
    code: str
    message: str
