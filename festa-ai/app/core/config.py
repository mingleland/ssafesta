"""Boot-time application configuration.

`Settings` is instantiated once at import time (see `settings` below). Any
invalid or missing value raises `pydantic.ValidationError` during that
import, which crashes the process before uvicorn binds a port — this is
the fail-fast behavior spec 007 plan.md Section 10 requires.
"""

from __future__ import annotations

from typing import Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def _parse_csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
        # A cross-field model_validator(mode="after") that raises ValueError
        # (e.g. _validate_embedding_dimension, _validate_job_recovery) gets
        # wrapped by pydantic-core with the *raw pre-coercion input dict* for
        # the whole model attached as `input_value=...` — this happens
        # regardless of individual field types, so SecretStr alone does not
        # stop it (SecretStr only masks a successfully-built instance's
        # repr()/str()/model_dump(), and field-level errors on that one
        # field). hide_input_in_errors strips that raw dict from the
        # rendered message, which is what reaches Docker stdout / the log
        # aggregator on a boot crash (spec 007 plan.md §9 "mask credentials
        # in logs").
        hide_input_in_errors=True,
    )

    # Runtime
    app_env: str = "local"
    log_level: str = "INFO"

    # Database (runtime role only; alembic reads MIGRATION_DATABASE_URL directly)
    database_url: SecretStr = Field(min_length=1)

    # Worker lease and recovery — spec 007 plan.md Section 10 / research.md Section 5
    job_heartbeat_seconds: int = Field(default=30, gt=0)
    job_lease_seconds: int = Field(default=90, gt=0)
    job_sweeper_seconds: int = Field(default=60, gt=0)
    job_max_retries: int = Field(default=3, ge=0)
    job_retry_backoff_seconds_csv: str = Field(
        default="60,300,900", validation_alias="JOB_RETRY_BACKOFF_SECONDS"
    )

    # Document limits and chunk tuning — spec 007 FR-011, FR-018, FR-020
    document_max_bytes: int = Field(default=20_971_520, gt=0)
    agent_document_max_count: int = Field(default=10, gt=0)
    agent_document_max_total_bytes: int = Field(default=104_857_600, gt=0)
    chunk_size: int = Field(default=900, gt=0)
    chunk_overlap: int = Field(default=180, ge=0)

    @field_validator(
        "embedding_api_key",
        "gms_api_key",
        mode="before",
    )
    @classmethod
    def _blank_to_none(cls, value: object) -> object:
        """Treat a blank/whitespace-only value as absent.

        `.env.example` may leave optional Secret values blank. python-dotenv parses
        `KEY=` as the env var being present with value `""`, not absent — so without this coercion
        pydantic tries to parse `""` as the declared type (int, or
        `SecretStr` for `embedding_api_key`) and crashes uvicorn on boot
        with an unmodified `.env.example`-derived `.env`. Any other value
        (including a real `None` from a non-dotenv source, or a non-blank
        string like "512" or "text-embedding-3-small") passes through
        unchanged for normal coercion.

        Non-Secret tuning fields get their own blank-to-default validators below.
        """
        if isinstance(value, str) and value.strip() == "":
            return None
        return value

    @field_validator("chunk_size", mode="before")
    @classmethod
    def _blank_chunk_size_to_spike_default(cls, value: object) -> object:
        return 900 if isinstance(value, str) and value.strip() == "" else value

    @field_validator("chunk_overlap", mode="before")
    @classmethod
    def _blank_chunk_overlap_to_spike_default(cls, value: object) -> object:
        return 180 if isinstance(value, str) and value.strip() == "" else value

    @field_validator("embedding_model_id", mode="before")
    @classmethod
    def _blank_embedding_model_to_spike_default(cls, value: object) -> object:
        return (
            "text-embedding-3-large"
            if isinstance(value, str) and value.strip() == ""
            else value
        )

    @field_validator("embedding_api_base_url", mode="before")
    @classmethod
    def _blank_embedding_url_to_gms_default(cls, value: object) -> object:
        return (
            "https://gms.ssafy.io/gmsapi/api.openai.com"
            if isinstance(value, str) and value.strip() == ""
            else value
        )

    @field_validator("embedding_provider", mode="before")
    @classmethod
    def _blank_embedding_provider_to_default(cls, value: object) -> object:
        """Blank `EMBEDDING_PROVIDER` falls back to the `"mock"` default.

        Unlike the `str | None` fields above, this field has no `None`
        member in its `Literal["mock", "gms"]` type — passing `None` through
        would fail type validation instead of resolving to the default.
        """
        if isinstance(value, str) and value.strip() == "":
            return "mock"
        return value

    @field_validator("embedding_api_path", mode="before")
    @classmethod
    def _blank_embedding_api_path_to_default(cls, value: object) -> object:
        """Blank `EMBEDDING_API_PATH` falls back to the `/v1/embeddings` default."""
        if isinstance(value, str) and value.strip() == "":
            return "/v1/embeddings"
        return value

    @field_validator("llm_provider", mode="before")
    @classmethod
    def _blank_llm_provider_to_default(cls, value: object) -> object:
        return "mock" if isinstance(value, str) and value.strip() == "" else value

    @field_validator("llm_api_base_url", mode="before")
    @classmethod
    def _blank_llm_url_to_gms_default(cls, value: object) -> object:
        return (
            "https://gms.ssafy.io/gmsapi/api.openai.com"
            if isinstance(value, str) and value.strip() == ""
            else value
        )

    @field_validator("llm_api_path", mode="before")
    @classmethod
    def _blank_llm_api_path_to_default(cls, value: object) -> object:
        return (
            "/v1/chat/completions"
            if isinstance(value, str) and value.strip() == ""
            else value
        )

    # Embedding provider — spec 007 FR-009 / 헌법 18조
    embedding_dimension: int = 1536
    embedding_model_id: str = "text-embedding-3-large"
    embedding_provider: Literal["mock", "gms"] = "mock"
    embedding_api_base_url: str = "https://gms.ssafy.io/gmsapi/api.openai.com"
    embedding_api_path: str = "/v1/embeddings"
    embedding_api_key: SecretStr | None = None

    # RAG generation defaults — S15P21A604-370..372 spike decisions
    retrieval_top_k: int = Field(default=10, gt=0)
    rag_context_top_n: int = Field(default=5, gt=0)
    rag_input_token_budget: int = Field(default=8_000, gt=0)
    rag_tokenizer_encoding: str = Field(default="cl100k_base", min_length=1)
    llm_model_id: str = Field(default="gpt-4.1-mini", min_length=1)
    llm_temperature: float = Field(default=0.0, ge=0.0, le=2.0)
    llm_provider: Literal["mock", "gms"] = "mock"
    llm_api_base_url: str = "https://gms.ssafy.io/gmsapi/api.openai.com"
    llm_api_path: str = "/v1/chat/completions"
    llm_connect_timeout_seconds: float = Field(default=10.0, gt=0)
    llm_read_timeout_seconds: float = Field(default=15.0, gt=0)
    gms_api_key: SecretStr | None = None

    # Spring internal callback — spec 007 plan.md Section 9
    spring_internal_base_url: str = Field(min_length=1)
    internal_spring_to_ai_tokens_csv: SecretStr = Field(
        min_length=1, validation_alias="INTERNAL_SPRING_TO_AI_TOKENS"
    )
    internal_ai_to_spring_tokens_csv: SecretStr = Field(
        min_length=1, validation_alias="INTERNAL_AI_TO_SPRING_TOKENS"
    )

    # R2 object storage (primary) — spec 007 FR-010, FR-030
    r2_endpoint: str = Field(min_length=1)
    r2_bucket: str = Field(min_length=1)
    r2_access_key_id: SecretStr = Field(min_length=1)
    r2_secret_access_key: SecretStr = Field(min_length=1)
    # boto3 requires a region_name even for R2 (S3-compatible API) — R2 itself
    # is region-less, so "auto" is the documented literal value (팀 결정 필요사항 §①).
    r2_region: str = "auto"

    # MinIO object storage (operator-approved manual fallback only) — spec 007 FR-030..033
    minio_endpoint: str = Field(min_length=1)
    minio_bucket: str = Field(min_length=1)
    minio_access_key_id: SecretStr = Field(min_length=1)
    minio_secret_access_key: SecretStr = Field(min_length=1)

    @property
    def job_retry_backoff_seconds(self) -> list[int]:
        return [int(part) for part in _parse_csv(self.job_retry_backoff_seconds_csv)]

    @property
    def internal_spring_to_ai_tokens(self) -> list[str]:
        return _parse_csv(self.internal_spring_to_ai_tokens_csv.get_secret_value())

    @property
    def internal_ai_to_spring_tokens(self) -> list[str]:
        return _parse_csv(self.internal_ai_to_spring_tokens_csv.get_secret_value())

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
    def _validate_rag_tuning(self) -> "Settings":
        if self.chunk_overlap >= self.chunk_size:
            raise ValueError(
                "CHUNK_OVERLAP must be smaller than CHUNK_SIZE "
                f"(overlap={self.chunk_overlap}, size={self.chunk_size})"
            )
        if self.rag_context_top_n > self.retrieval_top_k:
            raise ValueError(
                "RAG_CONTEXT_TOP_N must not exceed RETRIEVAL_TOP_K "
                f"(top_n={self.rag_context_top_n}, top_k={self.retrieval_top_k})"
            )
        return self

    @model_validator(mode="after")
    def _validate_embedding_provider(self) -> "Settings":
        if self.embedding_provider != "gms":
            return self

        required = {
            "EMBEDDING_API_KEY or GMS_API_KEY": (
                self.embedding_api_key or self.gms_api_key
            ),
        }
        missing = [env_name for env_name, value in required.items() if value is None]
        if missing:
            raise ValueError(
                "GMS embedding provider requires: " + ", ".join(sorted(missing))
            )
        return self

    @model_validator(mode="after")
    def _validate_llm_provider(self) -> "Settings":
        if self.llm_provider == "gms" and self.gms_api_key is None:
            raise ValueError("GMS LLM provider requires: GMS_API_KEY")
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
        if set(self.internal_spring_to_ai_tokens) & set(self.internal_ai_to_spring_tokens):
            raise ValueError(
                "INTERNAL_SPRING_TO_AI_TOKENS and INTERNAL_AI_TO_SPRING_TOKENS "
                "must not share tokens"
            )
        return self


settings = Settings()
