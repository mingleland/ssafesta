import importlib
import pathlib
import sys

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from tests.unit.test_config import VALID_ENV, _set_env


def _fresh_app_module():
    sys.modules.pop("app.core.config", None)
    sys.modules.pop("app.main", None)
    return importlib.import_module("app.main")


def test_health_live_returns_200_with_valid_config(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path)
    main = _fresh_app_module()

    client = TestClient(main.app)
    response = client.get("/ai/v1/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_app_state_exposes_loaded_settings(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path)
    main = _fresh_app_module()

    assert main.app.state.settings.embedding_dimension == 1536


def test_import_crashes_on_invalid_config(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path, omit={"DATABASE_URL"})

    with pytest.raises(ValidationError):
        _fresh_app_module()
