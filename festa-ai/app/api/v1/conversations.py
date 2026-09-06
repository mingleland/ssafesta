"""Expose the Conversation creation endpoint (spec 008 FR-024~027).

Only `POST /conversations` — `S15P21A604-126`'s scope. Close (DELETE) and the
message-streaming endpoint belong to 127/140.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Request, status

from app.api.errors import ApiError
from app.api.schemas.conversations import (
    ConversationResponse,
    CreateConversationRequest,
)
from app.api.schemas.documents import ErrorResponse
from app.clients.spring_booth_access import SpringBoothAccessClient
from app.core.auth import AuthenticatedMember, require_member
from app.repositories.conversation_repository import ConversationRepository
from app.services.conversation_service import (
    BoothAccessDenied,
    ConversationCreationFailed,
    ConversationService,
)

router = APIRouter(prefix="/conversations", tags=["conversations"])


async def get_conversation_service(request: Request) -> ConversationService:
    settings = request.app.state.settings
    spring_client = SpringBoothAccessClient(
        base_url=settings.spring_internal_base_url,
        service_token=settings.internal_ai_to_spring_tokens[0],
        timeout_seconds=settings.spring_booth_access_timeout_seconds,
        client=request.app.state.spring_http_client,
    )
    repository = ConversationRepository(
        request.app.state.redis, ttl_seconds=settings.conversation_ttl_seconds
    )
    return ConversationService(
        spring_client=spring_client,
        repository=repository,
        ttl_seconds=settings.conversation_ttl_seconds,
    )


@router.post(
    "",
    response_model=ConversationResponse,
    status_code=status.HTTP_201_CREATED,
    operation_id="createConversation",
    summary="로그인 사용자의 AI Conversation 생성",
    responses={
        status.HTTP_401_UNAUTHORIZED: {
            "description": "게스트 또는 인증 실패",
        },
        status.HTTP_403_FORBIDDEN: {
            "description": "Lease·Agent 소속·ACTIVE 검증 실패",
            "model": ErrorResponse,
        },
        status.HTTP_503_SERVICE_UNAVAILABLE: {
            "description": "Spring 검증 최종 실패, Fail Closed",
            "model": ErrorResponse,
        },
    },
)
async def create_conversation(
    payload: CreateConversationRequest,
    member: Annotated[AuthenticatedMember, Depends(require_member)],
    service: Annotated[ConversationService, Depends(get_conversation_service)],
) -> ConversationResponse:
    try:
        conversation = await service.create(
            user_id=member.user_id,
            booth_id=payload.booth_id,
            agent_id=payload.agent_id,
        )
    except BoothAccessDenied as exc:
        raise ApiError(
            status_code=status.HTTP_403_FORBIDDEN,
            code=exc.denial_code,
            message="부스 접근 권한을 확인할 수 없습니다.",
        ) from exc
    except ConversationCreationFailed as exc:
        raise ApiError(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            code="SPRING_UNAVAILABLE",
            message="일시적으로 대화를 시작할 수 없습니다. 잠시 후 다시 시도해주세요.",
        ) from exc

    return ConversationResponse(
        conversation_id=conversation.conversation_id,
        expires_at=conversation.expires_at,
    )
