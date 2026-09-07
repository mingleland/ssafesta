from fastapi import APIRouter, Request, Response, status

from app.api.v1.conversations import router as conversations_router
from app.api.v1.documents import router as documents_router

router = APIRouter()
router.include_router(documents_router)
router.include_router(conversations_router)


@router.get("/health/live", tags=["health"])
async def live() -> dict[str, str]:
    return {"status": "UP"}


@router.get("/health/ready", tags=["health"])
async def ready(request: Request, response: Response) -> dict[str, str]:
    supervisor = request.app.state.document_task_supervisor
    if not supervisor.accepting:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {"status": "NOT_READY"}
    return {"status": "UP"}
