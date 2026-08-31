"""Verify the Spring-to-FastAPI bearer-token boundary and sanitized failures."""

from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.api.dependencies.internal_auth import require_spring_service_token
from app.api.errors import ApiError, api_error_handler


def _client(tokens: list[str]) -> TestClient:
    app = FastAPI()
    app.state.settings = SimpleNamespace(internal_spring_to_ai_tokens=tokens)
    app.add_exception_handler(ApiError, api_error_handler)

    @app.get("/protected", dependencies=[Depends(require_spring_service_token)])
    async def protected() -> dict[str, str]:
        return {"status": "OK"}

    return TestClient(app)


@pytest.mark.parametrize("token", ["old-token", "new-token"])
def test_internal_auth_accepts_each_rotation_token(token: str) -> None:
    response = _client(["old-token", "new-token"]).get(
        "/protected", headers={"Authorization": f"Bearer {token}"}
    )

    assert response.status_code == 200
    assert response.json() == {"status": "OK"}


@pytest.mark.parametrize(
    "authorization",
    [None, "Basic spring-token", "Bearer wrong-token", "Bearer ai-to-spring-token"],
)
def test_internal_auth_rejects_missing_malformed_wrong_and_reverse_tokens(
    authorization: str | None,
) -> None:
    headers = {} if authorization is None else {"Authorization": authorization}

    response = _client(["spring-token"]).get("/protected", headers=headers)

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json() == {
        "code": "INVALID_SERVICE_TOKEN",
        "message": "유효하지 않은 내부 서비스 인증 정보입니다.",
    }
    assert "wrong-token" not in response.text
    assert "ai-to-spring-token" not in response.text

