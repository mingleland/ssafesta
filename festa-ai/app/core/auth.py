"""Verify the React-issued Access Token without accepting Refresh Tokens.

Spring signs Access Tokens with HS512 using a base64 `JWT_SECRET` shared with
this service (`AccessTokenService`/`JwtConfiguration`, backend/.../auth). A
non-`MEMBER` `role` claim (issued for guests as `"GUEST"`) is rejected here so
FR-027 holds without a second call to Spring on the hot path.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Annotated

import jwt
from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.api.errors import ApiError

_bearer = HTTPBearer(
    auto_error=False,
    scheme_name="userAccessToken",
    bearerFormat="JWT",
)


@dataclass(frozen=True, slots=True)
class AuthenticatedMember:
    user_id: int


async def require_member(
    request: Request,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> AuthenticatedMember:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise _unauthenticated()

    try:
        claims = jwt.decode(
            credentials.credentials,
            request.app.state.settings.jwt_secret_key,
            algorithms=["HS512"],
        )
    except jwt.PyJWTError as exc:
        raise _unauthenticated() from exc

    if claims.get("role") != "MEMBER":
        raise _unauthenticated()

    try:
        user_id = int(claims.get("sub"))
    except (TypeError, ValueError) as exc:
        raise _unauthenticated() from exc

    return AuthenticatedMember(user_id=user_id)


def _unauthenticated() -> ApiError:
    return ApiError(
        status_code=401,
        code="UNAUTHENTICATED",
        message="로그인이 필요합니다.",
        headers={"WWW-Authenticate": "Bearer"},
    )
