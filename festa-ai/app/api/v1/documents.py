"""Expose authenticated document Job intake endpoints backed by the AI DB."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies.internal_auth import require_spring_service_token
from app.api.errors import ApiError
from app.api.schemas.documents import (
    ErrorResponse,
    ProcessDocumentRequest,
    ProcessDocumentResponse,
)
from app.db.session import get_ai_db_session
from app.repositories.document_job_repository import DocumentJobRepository
from app.services.document_snapshot_validator import SnapshotConflictError

router = APIRouter(
    prefix="/documents",
    tags=["documents"],
    dependencies=[Depends(require_spring_service_token)],
)


async def get_document_job_repository(
    session: Annotated[AsyncSession, Depends(get_ai_db_session)],
) -> DocumentJobRepository:
    return DocumentJobRepository(session)


@router.post(
    "/process",
    response_model=ProcessDocumentResponse,
    status_code=status.HTTP_202_ACCEPTED,
    operation_id="enqueueDocumentProcessing",
    summary="문서 처리 Job 접수",
    description=(
        "동일 documentId에 활성 Job이 있으면 새 Job을 만들지 않고 기존 Job을 반환한다."
    ),
    response_description="새 Job 접수 또는 기존 활성 Job 반환",
    responses={
        status.HTTP_401_UNAUTHORIZED: {
            "description": "Spring→FastAPI Service Token 누락·오류 또는 반대 방향 토큰 사용",
        },
        status.HTTP_409_CONFLICT: {
            "description": "요청 snapshot이 현재 문서 개정본과 일치하지 않음",
            "model": ErrorResponse,
        },
        status.HTTP_422_UNPROCESSABLE_CONTENT: {
            "description": "문서 범위 또는 요청 형식 검증 실패",
            "model": ErrorResponse,
        },
    },
)
async def enqueue_document_processing(
    snapshot: ProcessDocumentRequest,
    request: Request,
    repository: Annotated[DocumentJobRepository, Depends(get_document_job_repository)],
) -> ProcessDocumentResponse:
    try:
        result = await repository.enqueue(
            snapshot,
            max_retries=request.app.state.settings.job_max_retries,
        )
    except SnapshotConflictError as exc:
        raise ApiError(
            status_code=status.HTTP_409_CONFLICT,
            code="DOCUMENT_SNAPSHOT_CONFLICT",
            message="이미 접수된 활성 Job의 문서 정보와 요청이 일치하지 않습니다.",
        ) from exc

    return ProcessDocumentResponse(
        job_id=f"job_{result.job.id}",
        document_id=result.job.document_id,
        status=result.job.status,
        existing=result.existing,
    )
