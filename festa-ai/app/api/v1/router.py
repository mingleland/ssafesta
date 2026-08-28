from fastapi import APIRouter

from app.api.v1.documents import router as documents_router

router = APIRouter()
router.include_router(documents_router)


@router.get("/health/live", tags=["health"])
async def live() -> dict[str, str]:
    return {"status": "UP"}
