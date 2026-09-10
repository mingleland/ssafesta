"""Spring 소유 문서 Job attempt를 인증 후 프로세스 수명에 묶인 Worker에 전달한다."""

from __future__ import annotations

from typing import TYPE_CHECKING, Annotated

import httpx
from fastapi import APIRouter, Depends, Request, Response, status

from app.api.dependencies.internal_auth import require_spring_service_token
from app.api.errors import ApiError
from app.api.schemas.documents import (
    CancelDocumentProcessingRequest,
    ErrorResponse,
    ProcessDocumentRequest,
)
from app.clients.spring_booth_access import SpringBoothAccessClient
from app.clients.spring_document_result import SpringDocumentResultClient
from app.providers.document_parser import DefaultDocumentParser
from app.providers.embedding import EmbeddingProvider
from app.providers.factory import create_object_storage
from app.services.document_processing_orchestrator import DocumentProcessingOrchestrator
from app.services.document_processing_service import (
    DocumentEmbeddingService,
    ProcessingSnapshot,
)
from app.services.text_chunker import TikTokenCodec
from app.workers.document_task_supervisor import (
    DocumentTaskSupervisor,
    SupervisorClosedError,
)

if TYPE_CHECKING:
    from app.core.config import Settings

router = APIRouter(
    prefix="/documents",
    tags=["documents"],
    dependencies=[Depends(require_spring_service_token)],
)


def build_document_processing_orchestrator(
    *,
    settings: Settings,
    spring_http_client: httpx.AsyncClient,
    embedding_provider: EmbeddingProvider,
) -> DocumentProcessingOrchestrator:
    """프로세스가 공유할 문서 처리 파이프라인을 한 번 조립한다."""
    result_client = SpringDocumentResultClient(
        base_url=settings.spring_internal_base_url,
        service_token=settings.internal_ai_to_spring_tokens[0],
        timeout_seconds=settings.spring_document_result_timeout_seconds,
        client=spring_http_client,
    )
    booth_access_client = SpringBoothAccessClient(
        base_url=settings.spring_internal_base_url,
        service_token=settings.internal_ai_to_spring_tokens[0],
        timeout_seconds=settings.spring_booth_access_timeout_seconds,
        client=spring_http_client,
    )
    embedding_service = DocumentEmbeddingService(
        storage_factory=lambda provider: create_object_storage(settings, provider),
        parser=DefaultDocumentParser(),
        embedding_provider=embedding_provider,
        chunk_size=settings.chunk_size,
        chunk_overlap=settings.chunk_overlap,
        codec=TikTokenCodec(),
        embedding_batch_size=settings.embedding_batch_size,
    )
    return DocumentProcessingOrchestrator(
        embedding_service=embedding_service,
        result_client=result_client,
        booth_access_client=booth_access_client,
        heartbeat_interval_seconds=settings.job_heartbeat_seconds,
    )


def get_document_task_supervisor(request: Request) -> DocumentTaskSupervisor:
    return request.app.state.document_task_supervisor


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
    supervisor: Annotated[
        DocumentTaskSupervisor, Depends(get_document_task_supervisor)
    ],
) -> Response:
    try:
        supervisor.submit(
            ProcessingSnapshot(
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
        )
    except SupervisorClosedError as exc:
        raise ApiError(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            code="WORKER_DRAINING",
            message="문서 처리 Worker가 종료 중입니다.",
            headers={"Retry-After": "1"},
        ) from exc
    return Response(status_code=status.HTTP_202_ACCEPTED)


@router.post(
    "/cancel",
    status_code=status.HTTP_204_NO_CONTENT,
    operation_id="cancelDocumentProcessing",
    summary="실행 중인 문서 처리 attempt 취소",
    description=(
        "같은 jobId+attemptNo가 이미 없거나 끝났어도 성공으로 응답한다(멱등). "
        "attemptNo가 현재 실행 중인 것과 다르면 아무것도 하지 않고 성공으로 응답한다. "
        "취소 대상 조회는 FastAPI 프로세스 하나의 in-memory 상태만 본다 — replica가 2개 "
        "이상이면 이 API는 요청을 받은 프로세스가 아닌 다른 프로세스의 attempt를 취소하지 "
        "못한다."
    ),
    responses={
        status.HTTP_204_NO_CONTENT: {
            "description": "취소 요청 수락(실제 취소 여부 무관 — 멱등)",
            "content": None,
        },
        status.HTTP_422_UNPROCESSABLE_CONTENT: {
            "description": "요청 형식 검증 실패",
            "model": ErrorResponse,
        },
        status.HTTP_401_UNAUTHORIZED: {
            "description": "Spring→FastAPI Service Token 누락·오류",
        },
    },
)
async def cancel_document_processing(
    request: CancelDocumentProcessingRequest,
    supervisor: Annotated[
        DocumentTaskSupervisor, Depends(get_document_task_supervisor)
    ],
) -> Response:
    supervisor.cancel(job_id=request.job_id, attempt_no=request.attempt_no)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
