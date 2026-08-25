"""Boot-time application configuration.

`Settings` is instantiated once at import time (see `settings` below). Any
invalid or missing value raises `pydantic.ValidationError` during that
import, which crashes the process before uvicorn binds a port — this is
the fail-fast behavior spec 007 plan.md Section 10 requires.
"""

from __future__ import annotations

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def _parse_csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # Runtime
    app_env: str = "local"
    log_level: str = "INFO"

    # Database (runtime role only; alembic reads MIGRATION_DATABASE_URL directly)
    database_url: str = Field(min_length=1)

    # Worker lease and recovery — spec 007 plan.md Section 10 / research.md Section 5
    job_heartbeat_seconds: int = 30
    job_lease_seconds: int = 90
    job_sweeper_seconds: int = 60
    job_max_retries: int = 3
    job_retry_backoff_seconds_csv: str = Field(
        default="60,300,900", validation_alias="JOB_RETRY_BACKOFF_SECONDS"
    )

    # Document limits and chunk tuning — spec 007 FR-011, FR-018, FR-020
    document_max_bytes: int = 20_971_520
    agent_document_max_count: int = 10
    agent_document_max_total_bytes: int = 104_857_600
    chunk_size: int | None = None
    chunk_overlap: int | None = None

    @field_validator("chunk_size", "chunk_overlap", mode="before")
    @classmethod
    def _blank_to_none(cls, value: object) -> object:
        """Treat a blank/whitespace-only value as absent.

        `.env.example` ships `CHUNK_SIZE=` / `CHUNK_OVERLAP=` (blank) as the
        documented "optional, no fixed default" tuning values (spec 007).
        python-dotenv parses `KEY=` as the env var being present with value
        `""`, not absent — so without this coercion pydantic tries to parse
        `""` as `int` and crashes uvicorn on boot with an unmodified
        `.env.example`-derived `.env`. Any other value (including a real
        `None` or `int` from a non-dotenv source, or an int-looking string
        like "512") passes through unchanged for normal int coercion.
        """
        if isinstance(value, str) and value.strip() == "":
            return None
        return value

    # Embedding provider — spec 007 FR-009 / 헌법 18조
    embedding_dimension: int = 1536
    embedding_model_id: str | None = None
    embedding_provider: str | None = None
    embedding_api_base_url: str | None = None
    embedding_api_key: str | None = None

    # Spring internal callback — spec 007 plan.md Section 9
    spring_internal_base_url: str = Field(min_length=1)
    internal_spring_to_ai_tokens_csv: str = Field(
        min_length=1, validation_alias="INTERNAL_SPRING_TO_AI_TOKENS"
    )
    internal_ai_to_spring_tokens_csv: str = Field(
        min_length=1, validation_alias="INTERNAL_AI_TO_SPRING_TOKENS"
    )

    # R2 object storage (primary) — spec 007 FR-010, FR-030
    r2_endpoint: str = Field(min_length=1)
    r2_bucket: str = Field(min_length=1)
    r2_access_key_id: str = Field(min_length=1)
    r2_secret_access_key: str = Field(min_length=1)

    # MinIO object storage (operator-approved manual fallback only) — spec 007 FR-030..033
    minio_endpoint: str = Field(min_length=1)
    minio_bucket: str = Field(min_length=1)
    minio_access_key_id: str = Field(min_length=1)
    minio_secret_access_key: str = Field(min_length=1)

    @property
    def job_retry_backoff_seconds(self) -> list[int]:
        return [int(part) for part in _parse_csv(self.job_retry_backoff_seconds_csv)]

    @property
    def internal_spring_to_ai_tokens(self) -> list[str]:
        return _parse_csv(self.internal_spring_to_ai_tokens_csv)

    @property
    def internal_ai_to_spring_tokens(self) -> list[str]:
        return _parse_csv(self.internal_ai_to_spring_tokens_csv)

    @model_validator(mode="after")
    def _validate_job_recovery(self) -> "Settings":
        if self.job_lease_seconds <= self.job_heartbeat_seconds:
            raise ValueError(
                "JOB_LEASE_SECONDS must be greater than JOB_HEARTBEAT_SECONDS "
                f"(lease={self.job_lease_seconds}, heartbeat={self.job_heartbeat_seconds})"
            )
        backoffs = self.job_retry_backoff_seconds
        if len(backoffs) != self.job_max_retries:
            raise ValueError(
                "JOB_RETRY_BACKOFF_SECONDS entry count must equal JOB_MAX_RETRIES "
                f"(backoffs={len(backoffs)}, max_retries={self.job_max_retries})"
            )
        return self

    @model_validator(mode="after")
    def _validate_embedding_dimension(self) -> "Settings":
        if self.embedding_dimension != 1536:
            raise ValueError(
                "EMBEDDING_DIMENSION is fixed at 1536 (헌법 18조 / spec 007 FR-009), "
                f"got {self.embedding_dimension}"
            )
        return self

    @model_validator(mode="after")
    def _validate_internal_tokens(self) -> "Settings":
        for env_name, tokens in (
            ("INTERNAL_SPRING_TO_AI_TOKENS", self.internal_spring_to_ai_tokens),
            ("INTERNAL_AI_TO_SPRING_TOKENS", self.internal_ai_to_spring_tokens),
        ):
            if not 1 <= len(tokens) <= 2:
                raise ValueError(
                    f"{env_name} must list 1-2 comma-separated tokens (got {len(tokens)})"
                )
            if len(set(tokens)) != len(tokens):
                raise ValueError(f"{env_name} must not contain duplicate tokens")
        return self


settings = Settings()
