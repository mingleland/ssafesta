import importlib
import pathlib
import sys

import pytest
from pydantic import ValidationError

VALID_ENV = {
    "DATABASE_URL": "postgresql+psycopg://user:pass@localhost:5432/festa",
    "SPRING_INTERNAL_BASE_URL": "http://spring.internal:8080",
    "INTERNAL_SPRING_TO_AI_TOKENS": "spring-to-ai-token-1",
    "INTERNAL_AI_TO_SPRING_TOKENS": "ai-to-spring-token-1",
    "R2_ENDPOINT": "https://accountid.r2.cloudflarestorage.com",
    "R2_BUCKET": "festa-documents",
    "R2_ACCESS_KEY_ID": "r2-access-key",
    "R2_SECRET_ACCESS_KEY": "r2-secret-key",
    "MINIO_ENDPOINT": "http://minio.internal:9000",
    "MINIO_BUCKET": "festa-documents-local",
    "MINIO_ACCESS_KEY_ID": "minio-access-key",
    "MINIO_SECRET_ACCESS_KEY": "minio-secret-key",
}


def _fresh_settings_module():
    """Re-import app.core.config so module-level `settings = Settings()` reruns."""
    sys.modules.pop("app.core.config", None)
    return importlib.import_module("app.core.config")


def _set_env(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: pathlib.Path,
    overrides: dict[str, str] | None = None,
    omit: set[str] | None = None,
) -> None:
    # A real `.env` file in the CWD would be picked up by pydantic-settings
    # (env_file=".env" is CWD-relative) and silently supply values that
    # monkeypatch.delenv/omit was supposed to leave absent, masking the
    # fail-fast tests below. chdir into an empty tmp_path so no such file
    # can exist regardless of the developer's actual working directory.
    monkeypatch.chdir(tmp_path)
    env = dict(VALID_ENV)
    if overrides:
        env.update(overrides)
    for key in omit or set():
        env.pop(key, None)
    for key, value in env.items():
        monkeypatch.setenv(key, value)


def test_valid_env_loads_with_documented_defaults(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path)
    config = _fresh_settings_module()

    assert config.settings.job_heartbeat_seconds == 30
    assert config.settings.job_lease_seconds == 90
    assert config.settings.job_sweeper_seconds == 60
    assert config.settings.job_max_retries == 3
    assert config.settings.job_retry_backoff_seconds == [60, 300, 900]
    assert config.settings.embedding_provider == "mock"
    assert config.settings.embedding_dimension == 1536
    assert config.settings.internal_spring_to_ai_tokens == ["spring-to-ai-token-1"]
    assert config.settings.internal_ai_to_spring_tokens == ["ai-to-spring-token-1"]


def test_missing_required_field_fails_fast(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path, omit={"DATABASE_URL"})
    with pytest.raises(ValidationError, match="database_url"):
        _fresh_settings_module()


def test_empty_string_required_field_fails_fast(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path, overrides={"R2_BUCKET": ""})
    with pytest.raises(ValidationError, match="r2_bucket"):
        _fresh_settings_module()


def test_lease_must_exceed_heartbeat(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(
        monkeypatch,
        tmp_path,
        overrides={"JOB_HEARTBEAT_SECONDS": "90", "JOB_LEASE_SECONDS": "90"},
    )
    with pytest.raises(ValidationError, match="JOB_LEASE_SECONDS"):
        _fresh_settings_module()


def test_backoff_count_must_match_max_retries(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path, overrides={"JOB_RETRY_BACKOFF_SECONDS": "60,300"})
    with pytest.raises(ValidationError, match="JOB_RETRY_BACKOFF_SECONDS"):
        _fresh_settings_module()


def test_embedding_dimension_is_locked_to_1536(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path, overrides={"EMBEDDING_DIMENSION": "768"})
    with pytest.raises(ValidationError, match="1536"):
        _fresh_settings_module()


def test_gms_embedding_provider_requires_all_provider_settings(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path, overrides={"EMBEDDING_PROVIDER": "gms"})

    with pytest.raises(ValidationError, match="GMS embedding provider requires"):
        _fresh_settings_module()


def test_gms_embedding_provider_loads_complete_settings(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(
        monkeypatch,
        tmp_path,
        overrides={
            "EMBEDDING_PROVIDER": "gms",
            "EMBEDDING_MODEL_ID": "gms-embedding-v1",
            "EMBEDDING_API_BASE_URL": "https://gms.example.test",
            "EMBEDDING_API_PATH": "/v1/embeddings",
            "EMBEDDING_API_KEY": "gms-secret-key",
        },
    )

    config = _fresh_settings_module()

    assert config.settings.embedding_provider == "gms"
    assert config.settings.embedding_model_id == "gms-embedding-v1"
    assert config.settings.embedding_api_path == "/v1/embeddings"
    assert "gms-secret-key" not in repr(config.settings)


def test_unknown_embedding_provider_fails_fast(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path, overrides={"EMBEDDING_PROVIDER": "unknown"})

    with pytest.raises(ValidationError, match="embedding_provider"):
        _fresh_settings_module()


@pytest.mark.parametrize(
    "env_key",
    ["INTERNAL_SPRING_TO_AI_TOKENS", "INTERNAL_AI_TO_SPRING_TOKENS"],
)
def test_internal_tokens_reject_more_than_two(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path, env_key: str
) -> None:
    _set_env(monkeypatch, tmp_path, overrides={env_key: "a,b,c"})
    with pytest.raises(ValidationError, match=env_key):
        _fresh_settings_module()


@pytest.mark.parametrize(
    "env_key",
    ["INTERNAL_SPRING_TO_AI_TOKENS", "INTERNAL_AI_TO_SPRING_TOKENS"],
)
def test_internal_tokens_reject_duplicates(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path, env_key: str
) -> None:
    _set_env(monkeypatch, tmp_path, overrides={env_key: "same-token,same-token"})
    with pytest.raises(ValidationError, match=env_key):
        _fresh_settings_module()


def test_internal_tokens_reject_cross_direction_reuse(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(
        monkeypatch,
        tmp_path,
        overrides={
            "INTERNAL_SPRING_TO_AI_TOKENS": "shared-token",
            "INTERNAL_AI_TO_SPRING_TOKENS": "shared-token",
        },
    )

    with pytest.raises(ValidationError, match="must not share tokens"):
        _fresh_settings_module()


def test_blank_chunk_tuning_env_vars_resolve_to_none(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    """Regression: `.env.example` ships CHUNK_SIZE= / CHUNK_OVERLAP= (blank).

    A real `.env` file (loaded via python-dotenv, not pytest's
    monkeypatch.setenv) parses `KEY=` as the var being *present* with value
    `""` — not absent. Simulate that here by explicitly setting the env var
    to the empty string, which previously crashed `Settings()` with a
    pydantic int_parsing ValidationError on `uvicorn app.main:app` boot.
    """
    _set_env(monkeypatch, tmp_path, overrides={"CHUNK_SIZE": "", "CHUNK_OVERLAP": ""})
    config = _fresh_settings_module()

    assert config.settings.chunk_size is None
    assert config.settings.chunk_overlap is None


def test_real_chunk_tuning_values_still_coerce_to_int(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    """The blank-string fix must not break normal int-looking values."""
    _set_env(monkeypatch, tmp_path, overrides={"CHUNK_SIZE": "512", "CHUNK_OVERLAP": "64"})
    config = _fresh_settings_module()

    assert config.settings.chunk_size == 512
    assert config.settings.chunk_overlap == 64


def test_blank_embedding_model_id_resolves_to_none(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    """Regression: `.env.example` ships EMBEDDING_MODEL_ID= (blank) too.

    Finding 4 — the blank-to-None validator originally only covered
    chunk_size/chunk_overlap; extend the same coverage check to the other
    blank-shipped `str | None` fields (embedding_model_id here is
    representative of embedding_api_base_url / embedding_api_key, which
    share the same validator). `embedding_provider` and `embedding_api_path`
    are NOT representative of this group — see the two tests below, they
    have their own blank-to-*default* validators instead.
    """
    _set_env(monkeypatch, tmp_path, overrides={"EMBEDDING_MODEL_ID": ""})
    config = _fresh_settings_module()

    assert config.settings.embedding_model_id is None


def test_blank_embedding_provider_resolves_to_mock_default(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    """Regression: a pre-this-diff `.env` still ships EMBEDDING_PROVIDER= (blank).

    `embedding_provider` is `Literal["mock", "gms"]`, not `str | None`, so a
    blank value must resolve to the `"mock"` default rather than `None`
    (`None` fails Literal validation and crashes boot).
    """
    _set_env(monkeypatch, tmp_path, overrides={"EMBEDDING_PROVIDER": ""})
    config = _fresh_settings_module()

    assert config.settings.embedding_provider == "mock"


def test_blank_embedding_api_path_resolves_to_default(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    """A blank EMBEDDING_API_PATH must fall back to `/v1/embeddings`, not `""`.

    `embedding_api_path` is plain `str`, not `str | None`; without its own
    blank-to-default validator, a blank value silently loads as `""` and
    ManagedEmbeddingProvider ends up calling the GMS base URL root instead
    of the embeddings endpoint.
    """
    _set_env(monkeypatch, tmp_path, overrides={"EMBEDDING_API_PATH": ""})
    config = _fresh_settings_module()

    assert config.settings.embedding_api_path == "/v1/embeddings"


def test_sweeper_seconds_zero_fails_fast(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    """Finding 2: JOB_SWEEPER_SECONDS=0 previously booted successfully."""
    _set_env(monkeypatch, tmp_path, overrides={"JOB_SWEEPER_SECONDS": "0"})
    with pytest.raises(ValidationError, match="job_sweeper_seconds"):
        _fresh_settings_module()


def test_document_max_bytes_negative_fails_fast(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    """Finding 2: DOCUMENT_MAX_BYTES=-1 previously booted successfully."""
    _set_env(monkeypatch, tmp_path, overrides={"DOCUMENT_MAX_BYTES": "-1"})
    with pytest.raises(ValidationError, match="document_max_bytes"):
        _fresh_settings_module()


def test_secrets_do_not_leak_in_validation_error(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    """Finding 1: a ValidationError must never echo raw secret values.

    pydantic's default ValidationError message includes the full input
    dict for the failing model. Before the SecretStr fix, this meant every
    credential value appeared in plaintext in the exception message — which
    reaches Docker stdout / the log aggregator on the exact crash path
    Task 3 exercises. Trigger any existing validator failure (embedding
    dimension lock) and confirm none of the plaintext secret values from
    VALID_ENV leak into the rendered error.
    """
    _set_env(monkeypatch, tmp_path, overrides={"EMBEDDING_DIMENSION": "768"})
    with pytest.raises(ValidationError) as exc_info:
        _fresh_settings_module()

    rendered = str(exc_info.value)
    assert "r2-secret-key" not in rendered
    assert "minio-secret-key" not in rendered
    assert "r2-access-key" not in rendered
    assert "minio-access-key" not in rendered
    assert "spring-to-ai-token-1" not in rendered
    assert "ai-to-spring-token-1" not in rendered


def test_secrets_do_not_leak_in_repr(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    """Finding 1: repr()/model_dump() of a valid Settings must mask secrets."""
    _set_env(monkeypatch, tmp_path)
    config = _fresh_settings_module()

    # Build a standalone instance directly (not the module-level singleton)
    # from the same env vars _set_env already populated.
    settings_instance = config.Settings()

    assert "r2-secret-key" not in repr(settings_instance)
    assert "minio-secret-key" not in repr(settings_instance)
