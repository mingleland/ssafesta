"""Verify the React-issued Access Token boundary rejects guests and bad tokens."""

from __future__ import annotations

import base64
from types import SimpleNamespace

import jwt
import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.api.errors import ApiError, api_error_handler
from app.core.auth import require_member

_SECRET = base64.b64encode(b"0" * 64).decode()
_SECRET_KEY = base64.b64decode(_SECRET)


def _token(*, role: str, subject: str, expired: bool = False) -> str:
    import time

    now = int(time.time())
    payload = {
        "sub": subject,
        "role": role,
        "iat": now,
        "exp": now - 60 if expired else now + 900,
    }
    return jwt.encode(payload, _SECRET_KEY, algorithm="HS512")


def _client() -> TestClient:
    app = FastAPI()
    app.state.settings = SimpleNamespace(jwt_secret_key=_SECRET_KEY)
    app.add_exception_handler(ApiError, api_error_handler)

    @app.get("/protected", dependencies=[Depends(require_member)])
    async def protected() -> dict[str, str]:
        return {"status": "OK"}

    return TestClient(app)


def test_accepts_valid_member_token() -> None:
    token = _token(role="MEMBER", subject="42")

    response = _client().get("/protected", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200


def test_rejects_guest_token_with_login_guidance() -> None:
    token = _token(role="GUEST", subject="guest:11111111-1111-1111-1111-111111111111")

    response = _client().get("/protected", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json() == {"code": "UNAUTHENTICATED", "message": "로그인이 필요합니다."}


def test_rejects_expired_token() -> None:
    token = _token(role="MEMBER", subject="42", expired=True)

    response = _client().get("/protected", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 401


def test_rejects_wrong_signature() -> None:
    other_key = base64.b64decode(base64.b64encode(b"1" * 64))
    token = jwt.encode(
        {"sub": "42", "role": "MEMBER", "exp": 9999999999}, other_key, algorithm="HS512"
    )

    response = _client().get("/protected", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 401


@pytest.mark.parametrize("authorization", [None, "Basic abc", "Bearer not-a-jwt"])
def test_rejects_missing_malformed_or_wrong_scheme(authorization: str | None) -> None:
    headers = {} if authorization is None else {"Authorization": authorization}

    response = _client().get("/protected", headers=headers)

    assert response.status_code == 401
