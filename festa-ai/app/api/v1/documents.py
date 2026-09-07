"""Spring 소유 문서 Job attempt를 인증 후 백그라운드 Worker에 전달한다."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response, status

from app.api.dependencies.internal_auth import require_spring_service_token
from app.api.schemas.documents import ErrorResponse, ProcessDocumentRequest
from app.workers.document_task_supervisor import DocumentTaskSupervisor

router = APIRouter(
    prefix="/documents",
    tags=["documents"],
    dependencies=[Depends(require_spring_service_token)],
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
    response_class=Response,
    response_description="비동기 처리 수락",
    responses={
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
    supervisor.submit(snapshot)
    return Response(status_code=status.HTTP_202_ACCEPTED)
