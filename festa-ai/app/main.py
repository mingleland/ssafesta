import logging

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError

from app.api.errors import (
    ApiError,
    api_error_handler,
    request_validation_error_handler,
)
from app.api.v1.router import router as api_v1_router
from app.core.config import settings
from app.db.session import create_ai_db_session_factory


def create_app() -> FastAPI:
    logging.basicConfig(level=settings.log_level)

    app = FastAPI(
        title="SSAFY FESTA AI Document Processing API",
        version="0.1.0",
    )
    app.state.settings = settings
    app.state.ai_db_session_factory = create_ai_db_session_factory(
        settings.database_url.get_secret_value()
    )
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, request_validation_error_handler)
    app.include_router(api_v1_router, prefix="/ai/v1")
    return app


app = create_app()
