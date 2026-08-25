import importlib
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


def _set_env(monkeypatch: pytest.MonkeyPatch, overrides: dict[str, str] | None = None, omit: set[str] | None = None) -> None:
    env = dict(VALID_ENV)
    if overrides:
        env.update(overrides)
    for key in omit or set():
        env.pop(key, None)
    for key, value in env.items():
        monkeypatch.setenv(key, value)


def test_valid_env_loads_with_documented_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_env(monkeypatch)
    config = _fresh_settings_module()

    assert config.settings.job_heartbeat_seconds == 30
    assert config.settings.job_lease_seconds == 90
    assert config.settings.job_sweeper_seconds == 60
    assert config.settings.job_max_retries == 3
    assert config.settings.job_retry_backoff_seconds == [60, 300, 900]
    assert config.settings.embedding_dimension == 1536
    assert config.settings.internal_spring_to_ai_tokens == ["spring-to-ai-token-1"]
    assert config.settings.internal_ai_to_spring_tokens == ["ai-to-spring-token-1"]


def test_missing_required_field_fails_fast(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_env(monkeypatch, omit={"DATABASE_URL"})
    with pytest.raises(ValidationError, match="database_url"):
        _fresh_settings_module()


def test_empty_string_required_field_fails_fast(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_env(monkeypatch, overrides={"R2_BUCKET": ""})
    with pytest.raises(ValidationError, match="r2_bucket"):
        _fresh_settings_module()


def test_lease_must_exceed_heartbeat(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_env(monkeypatch, overrides={"JOB_HEARTBEAT_SECONDS": "90", "JOB_LEASE_SECONDS": "90"})
    with pytest.raises(ValidationError, match="JOB_LEASE_SECONDS"):
        _fresh_settings_module()


def test_backoff_count_must_match_max_retries(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_env(monkeypatch, overrides={"JOB_RETRY_BACKOFF_SECONDS": "60,300"})
    with pytest.raises(ValidationError, match="JOB_RETRY_BACKOFF_SECONDS"):
        _fresh_settings_module()


def test_embedding_dimension_is_locked_to_1536(monkeypatch: pytest.MonkeyPatch) -> None:
    _set_env(monkeypatch, overrides={"EMBEDDING_DIMENSION": "768"})
    with pytest.raises(ValidationError, match="1536"):
        _fresh_settings_module()


@pytest.mark.parametrize(
    "env_key",
    ["INTERNAL_SPRING_TO_AI_TOKENS", "INTERNAL_AI_TO_SPRING_TOKENS"],
)
def test_internal_tokens_reject_more_than_two(monkeypatch: pytest.MonkeyPatch, env_key: str) -> None:
    _set_env(monkeypatch, overrides={env_key: "a,b,c"})
    with pytest.raises(ValidationError, match=env_key):
        _fresh_settings_module()


@pytest.mark.parametrize(
    "env_key",
    ["INTERNAL_SPRING_TO_AI_TOKENS", "INTERNAL_AI_TO_SPRING_TOKENS"],
)
def test_internal_tokens_reject_duplicates(monkeypatch: pytest.MonkeyPatch, env_key: str) -> None:
    _set_env(monkeypatch, overrides={env_key: "same-token,same-token"})
    with pytest.raises(ValidationError, match=env_key):
        _fresh_settings_module()
