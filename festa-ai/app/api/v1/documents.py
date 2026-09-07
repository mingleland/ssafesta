"""무상태 문서 처리 접수 endpoint (S15P21A604-449, S15P21A604-124).

FastAPI는 문서 Job을 소유하지 않는다 — Spring이 만든 Job의 `jobId`·`attemptNo`와
snapshot을 받아 202로 즉시 수락하고, 실제 처리(다운로드→검증→파싱→청킹→Embedding→
Spring 결과 전송)는 background task로 넘긴다. 같은 `jobId+attemptNo`가 아직 처리
중이면 두 번째 요청은 새 task를 만들지 않고 그대로 202를 반환한다 — 계약이 요구하는
멱등 수락이다.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response, status

from app.api.dependencies.internal_auth import require_spring_service_token
from app.api.schemas.documents import ErrorResponse, ProcessDocumentRequest
from app.clients.spring_document_result import SpringDocumentResultClient
from app.providers.document_parser import DefaultDocumentParser
from app.providers.factory import create_object_storage
from app.services.document_processing_orchestrator import DocumentProcessingOrchestrator
from app.services.document_processing_service import (
    DocumentEmbeddingService,
    ProcessingSnapshot,
)
from app.services.text_chunker import TikTokenCodec

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/documents",
    tags=["documents"],
    dependencies=[Depends(require_spring_service_token)],
)


async def get_document_processing_orchestrator(
    request: Request,
) -> DocumentProcessingOrchestrator:
    settings = request.app.state.settings
    result_client = SpringDocumentResultClient(
        base_url=settings.spring_internal_base_url,
        service_token=settings.internal_ai_to_spring_tokens[0],
        timeout_seconds=settings.spring_document_result_timeout_seconds,
        client=request.app.state.spring_http_client,
    )
    embedding_service = DocumentEmbeddingService(
        storage_factory=lambda provider: create_object_storage(settings, provider),
        parser=DefaultDocumentParser(),
        embedding_provider=request.app.state.embedding_provider,
        chunk_size=settings.chunk_size,
        chunk_overlap=settings.chunk_overlap,
        codec=TikTokenCodec(),
        embedding_batch_size=settings.embedding_batch_size,
    )
    return DocumentProcessingOrchestrator(
        embedding_service=embedding_service,
        result_client=result_client,
        heartbeat_interval_seconds=settings.job_heartbeat_seconds,
    )


@router.post(
    "/process",
    status_code=status.HTTP_202_ACCEPTED,
    operation_id="startDocumentProcessing",
    summary="영속 Job의 문서 처리 시작",
    description=(
        "FastAPI는 비동기 처리를 시작하고 DB에 Job을 만들지 않는다. "
        "같은 jobId+attemptNo 재전송은 중복 Worker 없이 멱등하게 수락한다."
    ),
    responses={
        status.HTTP_202_ACCEPTED: {
            "description": "비동기 처리 수락",
            "content": None,
        },
        status.HTTP_401_UNAUTHORIZED: {
            "description": "Spring→FastAPI Service Token 누락·오류",
        },
        status.HTTP_422_UNPROCESSABLE_CONTENT: {
            "description": "요청 형식·크기 검증 실패",
            "model": ErrorResponse,
        },
    },
)
async def start_document_processing(
    snapshot: ProcessDocumentRequest,
    request: Request,
    orchestrator: Annotated[
        DocumentProcessingOrchestrator, Depends(get_document_processing_orchestrator)
    ],
) -> Response:
    in_flight: set[tuple[int, int]] = request.app.state.in_flight_document_jobs
    key = (snapshot.job_id, snapshot.attempt_no)
    if key in in_flight:
        return Response(status_code=status.HTTP_202_ACCEPTED)
    in_flight.add(key)

    processing_snapshot = ProcessingSnapshot(
        job_id=snapshot.job_id,
        attempt_no=snapshot.attempt_no,
        document_id=snapshot.document_id,
        booth_id=snapshot.booth_id,
        agent_id=snapshot.agent_id,
        original_filename=snapshot.original_filename,
        content_type=snapshot.content_type,
        file_size_bytes=snapshot.file_size_bytes,
        storage_provider=snapshot.storage_provider,
        storage_bucket=snapshot.storage_bucket,
        object_key=snapshot.object_key,
        source_hash=snapshot.source_hash,
    )

    async def _run() -> None:
        try:
            await orchestrator.run(processing_snapshot)
        finally:
            in_flight.discard(key)

    asyncio.create_task(_run())
    return Response(status_code=status.HTTP_202_ACCEPTED)
