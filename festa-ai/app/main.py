import logging

from fastapi import FastAPI

from app.api.v1.router import router as api_v1_router
from app.core.config import settings


def create_app() -> FastAPI:
    logging.basicConfig(level=settings.log_level)

    app = FastAPI(
        title="SSAFY FESTA AI Document Processing API",
        version="0.1.0",
    )
    app.state.settings = settings
    app.include_router(api_v1_router, prefix="/ai/v1")
    return app


app = create_app()
