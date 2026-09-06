"""Expose the Conversation create and message-streaming endpoints.

`S15P21A604-126` — `POST /conversations` (spec 008 FR-024~027). `S15P21A604-140`
adds `POST /conversations/{id}/messages` (FR-005a, C-07). Close (DELETE)
belongs to 127.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Request, status
from fastapi.responses import StreamingResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import ApiError
from app.api.schemas.conversations import (
    ConversationResponse,
    CreateConversationRequest,
    MessageRequest,
)
from app.api.schemas.documents import ErrorResponse
from app.clients.spring_booth_access import SpringBoothAccessClient
from app.core.auth import AuthenticatedMember, require_member
from app.db.session import get_ai_db_session
from app.repositories.chunk_repository import ChunkRepository
from app.repositories.conversation_repository import ConversationRepository
from app.repositories.document_job_repository import DocumentJobRepository
from app.services.conversation_service import (
    BoothAccessDenied,
    ConversationCreationFailed,
    ConversationService,
)
from app.services.context_service import PromptBuilder
from app.services.rag_service import RagContextService
from app.services.stream_service import (
    BoothLeaseExpired,
    ConversationNotFound,
    ConversationOwnershipMismatch,
    ConversationStreamService,
)
from app.services.vector_search_service import VectorSearchService

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


async def get_stream_service(
    request: Request,
    session: Annotated[AsyncSession, Depends(get_ai_db_session)],
) -> ConversationStreamService:
    settings = request.app.state.settings
    repository = ConversationRepository(
        request.app.state.redis, ttl_seconds=settings.conversation_ttl_seconds
    )
    vector_search = VectorSearchService(
        embedding_provider=request.app.state.embedding_provider,
        chunk_repository=ChunkRepository(session),
    )
    rag_context_service = RagContextService(
        vector_search=vector_search,
        agent_config_provider=request.app.state.agent_config_provider,
        prompt_builder=PromptBuilder.from_settings(settings),
        retrieval_top_k=settings.retrieval_top_k,
    )
    return ConversationStreamService(
        repository=repository,
        rag_context_service=rag_context_service,
        llm_provider=request.app.state.llm_provider,
        document_job_repository=DocumentJobRepository(session),
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


@router.post(
    "/{conversationId}/messages",
    operation_id="streamConversationMessage",
    summary="질문을 보내고 정규화 SSE를 수신",
    responses={
        status.HTTP_200_OK: {
            "description": "C-07 SSE stream",
            "content": {"text/event-stream": {"schema": {"type": "string"}}},
        },
        status.HTTP_403_FORBIDDEN: {
            "description": "소유권 오류 또는 BOOTH_LEASE_EXPIRED",
            "model": ErrorResponse,
        },
        status.HTTP_404_NOT_FOUND: {
            "description": "Conversation 없음/TTL 만료",
            "model": ErrorResponse,
        },
    },
)
async def stream_conversation_message(
    conversationId: str,
    payload: MessageRequest,
    member: Annotated[AuthenticatedMember, Depends(require_member)],
    service: Annotated[ConversationStreamService, Depends(get_stream_service)],
) -> StreamingResponse:
    try:
        conversation = await service.authorize(
            conversation_id=conversationId, user_id=member.user_id
        )
    except ConversationNotFound as exc:
        raise ApiError(
            status_code=status.HTTP_404_NOT_FOUND,
            code="CONVERSATION_NOT_FOUND",
            message="Conversation을 찾을 수 없습니다.",
        ) from exc
    except ConversationOwnershipMismatch as exc:
        raise ApiError(
            status_code=status.HTTP_403_FORBIDDEN,
            code="CONVERSATION_OWNERSHIP_MISMATCH",
            message="다른 사용자의 Conversation입니다.",
        ) from exc
    except BoothLeaseExpired as exc:
        raise ApiError(
            status_code=status.HTTP_403_FORBIDDEN,
            code="BOOTH_LEASE_EXPIRED",
            message="부스 임대가 만료되었습니다.",
        ) from exc

    return StreamingResponse(
        service.stream(conversation=conversation, question=payload.question),
        media_type="text/event-stream",
    )
