from fastapi import APIRouter

from app.api.v1.conversations import router as conversations_router
from app.api.v1.documents import router as documents_router

router = APIRouter()
router.include_router(documents_router)
router.include_router(conversations_router)


@router.get("/health/live", tags=["health"])
async def live() -> dict[str, str]:
    return {"status": "UP"}
