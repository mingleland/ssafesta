from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware

from app.api.errors import (
    ApiError,
    api_error_handler,
    request_validation_error_handler,
)
from app.api.v1.documents import build_document_processing_orchestrator
from app.api.v1.router import router as api_v1_router
from app.clients.spring_agent_config import SpringAgentConfigClient
from app.core.config import settings
from app.core.logging import configure_logging
from app.core.redis import create_redis_client
from app.providers.factory import create_embedding_provider, create_llm_provider
from app.workers.document_task_supervisor import DocumentTaskSupervisor


async def _close_if_supported(resource: Any) -> None:
    close = getattr(resource, "aclose", None)
    if close is not None:
        await close()


def create_app() -> FastAPI:
    configure_logging(settings.log_level)

    redis = create_redis_client(settings.redis_url)
    spring_http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(
            settings.spring_booth_access_timeout_seconds,
            connect=settings.spring_booth_access_timeout_seconds,
        )
    )
    embedding_provider = create_embedding_provider(settings)
    llm_provider = create_llm_provider(settings)
    agent_config_provider = SpringAgentConfigClient(
        base_url=settings.spring_internal_base_url,
        service_token=settings.internal_ai_to_spring_tokens[0],
        timeout_seconds=settings.spring_agent_config_timeout_seconds,
        client=spring_http_client,
    )
    document_orchestrator = build_document_processing_orchestrator(
        settings=settings,
        spring_http_client=spring_http_client,
        embedding_provider=embedding_provider,
    )
    document_task_supervisor = DocumentTaskSupervisor(
        processor=document_orchestrator.run,
        max_concurrency=settings.document_worker_max_concurrency,
    )

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        try:
            yield
        finally:
            await document_task_supervisor.close(
                grace_seconds=settings.document_worker_shutdown_grace_seconds
            )
            await _close_if_supported(llm_provider)
            await _close_if_supported(embedding_provider)
            await spring_http_client.aclose()
            await redis.aclose()

    app = FastAPI(
        title="SSAFY FESTA AI Document Processing API",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.redis = redis
    app.state.spring_http_client = spring_http_client
    app.state.embedding_provider = embedding_provider
    app.state.llm_provider = llm_provider
    app.state.document_task_supervisor = document_task_supervisor
    app.state.agent_config_provider = agent_config_provider
    # 로컬 브라우저 검증 전용 — 배포 CORS 정책(same-origin vs 별도 호스트)은 아직 미정(docs/26).
    # Vite dev server(5173/5174)에서 festa-ai(8000)를 직접 호출할 때만 필요하다.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["http://localhost:5173", "http://localhost:5174"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, request_validation_error_handler)
    app.include_router(api_v1_router, prefix="/ai/v1")
    return app


app = create_app()
