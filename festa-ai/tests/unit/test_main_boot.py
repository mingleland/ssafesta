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


def test_health_ready_tracks_worker_acceptance(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path)
    main = _fresh_app_module()
    client = TestClient(main.app)

    ready = client.get("/ai/v1/health/ready")
    main.app.state.document_task_supervisor._accepting = False
    draining = client.get("/ai/v1/health/ready")

    assert ready.status_code == 200
    assert ready.json() == {"status": "UP"}
    assert draining.status_code == 503
    assert draining.json() == {"status": "NOT_READY"}


def test_lifespan_closes_worker_and_shared_http_client(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path)
    main = _fresh_app_module()

    with TestClient(main.app):
        assert main.app.state.document_task_supervisor.accepting is True
        assert main.app.state.spring_http_client.is_closed is False

    assert main.app.state.document_task_supervisor.accepting is False
    assert main.app.state.spring_http_client.is_closed is True


def test_app_state_exposes_loaded_settings(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path)
    main = _fresh_app_module()

    assert main.app.state.settings.embedding_dimension == 1536


def test_app_uses_real_spring_agent_config_client(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path)
    main = _fresh_app_module()

    from app.clients.spring_agent_config import SpringAgentConfigClient

    assert isinstance(main.app.state.agent_config_provider, SpringAgentConfigClient)
    assert main.app.state.agent_config_provider._client is main.app.state.spring_http_client


def test_app_wires_capacity_service_from_settings(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path)
    main = _fresh_app_module()

    from app.services.capacity_service import CapacityService

    assert isinstance(main.app.state.capacity_service, CapacityService)


def test_import_crashes_on_invalid_config(
    monkeypatch: pytest.MonkeyPatch, tmp_path: pathlib.Path
) -> None:
    _set_env(monkeypatch, tmp_path, omit={"REDIS_URL"})

    with pytest.raises(ValidationError):
        _fresh_app_module()
