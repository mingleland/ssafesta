"""Render stable, sanitized error bodies for the FastAPI boundary."""

from __future__ import annotations

from collections.abc import Mapping

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.schemas.documents import ErrorResponse


class ApiError(Exception):
    def __init__(
        self,
        *,
        status_code: int,
        code: str,
        message: str,
        headers: Mapping[str, str] | None = None,
    ) -> None:
        super().__init__(code)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.headers = headers


async def api_error_handler(_request: Request, exc: ApiError) -> JSONResponse:
    body = ErrorResponse(code=exc.code, message=exc.message)
    return JSONResponse(
        status_code=exc.status_code,
        content=body.model_dump(by_alias=True),
        headers=exc.headers,
    )


async def request_validation_error_handler(
    _request: Request, _exc: RequestValidationError
) -> JSONResponse:
    body = ErrorResponse(
        code="INVALID_REQUEST",
        message="요청 형식이 올바르지 않습니다.",
    )
    return JSONResponse(
        status_code=422,
        content=body.model_dump(by_alias=True),
    )
