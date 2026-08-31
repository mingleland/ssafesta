from fastapi import APIRouter

router = APIRouter()


@router.get("/health/live", tags=["health"])
async def live() -> dict[str, str]:
    return {"status": "UP"}
