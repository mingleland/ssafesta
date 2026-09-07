import logging

import httpx
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError

from app.api.errors import (
    ApiError,
    api_error_handler,
    request_validation_error_handler,
)
from app.api.v1.router import router as api_v1_router
from app.core.config import settings
from app.core.redis import create_redis_client
from app.providers.agent_config import MockAgentConfigProvider
from app.providers.factory import create_embedding_provider, create_llm_provider


def create_app() -> FastAPI:
    logging.basicConfig(level=settings.log_level)

    app = FastAPI(
        title="SSAFY FESTA AI Document Processing API",
        version="0.1.0",
    )
    app.state.settings = settings
    app.state.redis = create_redis_client(settings.redis_url)
    # S15P21A604-124: 같은 jobId+attemptNo 재전송을 중복 Worker 없이 멱등 수락하기
    # 위한 in-process 추적이다. FastAPI는 문서 DB가 없어 이 이상의 영속 상태를
    # 두지 않는다 (S15P21A604-449) — 재시작하면 비워지지만 Spring이 attemptNo로
    # 소유권을 판정하므로 안전하다.
    app.state.in_flight_document_jobs = set()
    app.state.spring_http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(
            settings.spring_booth_access_timeout_seconds,
            connect=settings.spring_booth_access_timeout_seconds,
        )
    )
    app.state.embedding_provider = create_embedding_provider(settings)
    app.state.llm_provider = create_llm_provider(settings)
    # Placeholder pending S15P21A604-399 (Spring Agent 추론 설정 조회 내부 API) —
    # see app/providers/agent_config.py.
    app.state.agent_config_provider = MockAgentConfigProvider()
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, request_validation_error_handler)
    app.include_router(api_v1_router, prefix="/ai/v1")
    return app


app = create_app()
