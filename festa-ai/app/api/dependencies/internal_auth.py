"""Authenticate Spring-to-FastAPI calls without exposing token details."""

from __future__ import annotations

import secrets
from typing import Annotated

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.api.errors import ApiError

_bearer = HTTPBearer(
    auto_error=False,
    scheme_name="serviceToken",
    bearerFormat="opaque-service-token",
    description=(
        "INTERNAL_SPRING_TO_AI_TOKENS의 첫 값을 Spring이 송신하고 "
        "FastAPI가 목록 전체를 상수 시간으로 검증한다."
    ),
)


async def require_spring_service_token(
    request: Request,
    credentials: Annotated[
        HTTPAuthorizationCredentials | None, Depends(_bearer)
    ],
) -> None:
    supplied_token = credentials.credentials if credentials is not None else ""

    # Evaluate every configured candidate so token order does not create an
    # early-exit timing signal during the supported two-token rotation window.
    matches = tuple(
        secrets.compare_digest(supplied_token, configured_token)
        for configured_token in request.app.state.settings.internal_spring_to_ai_tokens
    )
    if credentials is None or credentials.scheme.lower() != "bearer" or not any(matches):
        raise ApiError(
            status_code=401,
            code="INVALID_SERVICE_TOKEN",
            message="유효하지 않은 내부 서비스 인증 정보입니다.",
            headers={"WWW-Authenticate": "Bearer"},
        )
