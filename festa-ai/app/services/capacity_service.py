"""사용자·AI 직원·전체 동시 스트림 한도와 FIFO 대기열 (spec 008 FR-019/020, S15P21A604-142).

`asyncio.Semaphore`는 한 프로세스 안에서만 정확해 다중 FastAPI replica의 전역
한도(20)를 보장하지 못한다(`specs/008-ai-conversation-rag/research.md` §2). 그래서
사용자·AI 직원 동시성은 Redis `INCR`/`DECR`로, 전역 동시성+FIFO 대기열은 Redis
List의 `BLPOP`(여러 클라이언트가 같은 key를 기다리면 도착 순서로 깨어난다 —
Redis 문서 명세)으로 구현해 replica가 몇 개든 하나의 한도를 공유한다.

사용자·AI 직원 한도 초과는 대기열에 넣지 않고 즉시 거부한다. 전역 한도만 넘은
요청만 대기열(최대 `queue_capacity`, `queue_wait_seconds`초)에 들어간다.

알려진 한계: 프로세스가 강제 종료되어 `release()`가 실행되지 못하면, 사용자·AI
직원 카운터는 `active_lease_ttl_seconds` TTL로 스스로 회수되지만 전역 토큰
풀(`BLPOP` 대상 List)에는 TTL이 없어 토큰이 영구 유실될 수 있다. 이 프로젝트
규모(데모 목적, 단일/소수 인스턴스)에서는 감수 가능한 잔여 위험으로 판단했다.

FIFO 대기열 순서는 이 클래스가 직접 구현하지 않는다 — `BLPOP`으로 여러 클라이언트가
같은 key를 기다리면 블록한 순서대로 깨어난다는 Redis 자체 계약에 의존한다. 이
계약은 실제 Redis 서버 기준이며, 테스트 더블인 fakeredis(2.37)는 이 순서를
재현하지 않는다(실측: LIFO) — 그래서 순서 보장은 단위 테스트로 고정하지 않았고
`docs/25_트러블슈팅.md`에 한계로 기록했다.
"""

from __future__ import annotations

import time
import uuid
from collections.abc import Callable

from redis.asyncio import Redis

_GLOBAL_POOL_KEY = "ratelimit:global:pool"
_GLOBAL_POOL_READY_KEY = "ratelimit:global:pool:ready"
_GLOBAL_WAITING_KEY = "ratelimit:global:waiting"


class CapacityExceeded(Exception):
    """한도 초과 — 호출자는 429 + Retry-After로 응답해야 한다."""

    def __init__(self, retry_after_seconds: float) -> None:
        super().__init__(f"capacity exceeded, retry after {retry_after_seconds}s")
        self.retry_after_seconds = retry_after_seconds


class CapacityLease:
    """한 스트림이 점유한 사용자·AI 직원·전역 슬롯. 스트림 종료 시 반드시 release한다."""

    def __init__(self, *, service: "CapacityService", user_id: int, agent_id: int) -> None:
        self._service = service
        self._user_id = user_id
        self._agent_id = agent_id
        self._released = False

    async def release(self) -> None:
        if self._released:
            return
        self._released = True
        await self._service._release(user_id=self._user_id, agent_id=self._agent_id)


def _default_clock() -> float:
    return time.time()


class CapacityService:
    def __init__(
        self,
        *,
        redis: Redis,
        user_concurrency_limit: int,
        user_question_limit: int,
        user_question_window_seconds: float,
        agent_concurrency_limit: int,
        global_concurrency_limit: int,
        queue_capacity: int,
        queue_wait_seconds: float,
        active_lease_ttl_seconds: float,
        clock: Callable[[], float] = _default_clock,
    ) -> None:
        for name, value in (
            ("user_concurrency_limit", user_concurrency_limit),
            ("user_question_limit", user_question_limit),
            ("user_question_window_seconds", user_question_window_seconds),
            ("agent_concurrency_limit", agent_concurrency_limit),
            ("global_concurrency_limit", global_concurrency_limit),
            ("queue_capacity", queue_capacity),
            ("queue_wait_seconds", queue_wait_seconds),
            ("active_lease_ttl_seconds", active_lease_ttl_seconds),
        ):
            if value <= 0:
                raise ValueError(f"{name} must be positive")

        self._redis = redis
        self._user_concurrency_limit = user_concurrency_limit
        self._user_question_limit = user_question_limit
        self._user_question_window_seconds = user_question_window_seconds
        self._agent_concurrency_limit = agent_concurrency_limit
        self._global_concurrency_limit = global_concurrency_limit
        self._queue_capacity = queue_capacity
        self._queue_wait_seconds = queue_wait_seconds
        self._active_lease_ttl_seconds = active_lease_ttl_seconds
        self._clock = clock

    async def acquire(self, *, user_id: int, agent_id: int) -> CapacityLease:
        if not await self._try_increment(
            self._user_key(user_id), self._user_concurrency_limit
        ):
            raise CapacityExceeded(self._active_lease_ttl_seconds)

        if not await self._try_record_question(user_id):
            await self._decrement(self._user_key(user_id))
            retry_after = await self._question_window_retry_after(user_id)
            raise CapacityExceeded(retry_after)

        if not await self._try_increment(
            self._agent_key(agent_id), self._agent_concurrency_limit
        ):
            await self._decrement(self._user_key(user_id))
            raise CapacityExceeded(self._active_lease_ttl_seconds)

        if not await self._try_enter_global_queue():
            await self._decrement(self._agent_key(agent_id))
            await self._decrement(self._user_key(user_id))
            raise CapacityExceeded(self._queue_wait_seconds)

        try:
            await self._ensure_global_pool_ready()
            popped = await self._redis.blpop(
                [_GLOBAL_POOL_KEY], timeout=self._queue_wait_seconds
            )
        finally:
            await self._redis.decr(_GLOBAL_WAITING_KEY)

        if popped is None:
            await self._decrement(self._agent_key(agent_id))
            await self._decrement(self._user_key(user_id))
            raise CapacityExceeded(self._queue_wait_seconds)

        return CapacityLease(service=self, user_id=user_id, agent_id=agent_id)

    async def _release(self, *, user_id: int, agent_id: int) -> None:
        await self._redis.rpush(_GLOBAL_POOL_KEY, "1")
        await self._decrement(self._agent_key(agent_id))
        await self._decrement(self._user_key(user_id))

    async def _try_increment(self, key: str, limit: int) -> bool:
        count = await self._redis.incr(key)
        if count > limit:
            await self._redis.decr(key)
            return False
        await self._redis.expire(key, int(self._active_lease_ttl_seconds) + 1)
        return True

    async def _decrement(self, key: str) -> None:
        remaining = await self._redis.decr(key)
        if remaining <= 0:
            await self._redis.delete(key)

    async def _try_enter_global_queue(self) -> bool:
        waiting = await self._redis.incr(_GLOBAL_WAITING_KEY)
        if waiting > self._queue_capacity:
            await self._redis.decr(_GLOBAL_WAITING_KEY)
            return False
        await self._redis.expire(_GLOBAL_WAITING_KEY, int(self._queue_wait_seconds) + 1)
        return True

    async def _ensure_global_pool_ready(self) -> None:
        became_owner = await self._redis.set(_GLOBAL_POOL_READY_KEY, "1", nx=True)
        if became_owner:
            await self._redis.rpush(
                _GLOBAL_POOL_KEY, *(["1"] * self._global_concurrency_limit)
            )

    async def _try_record_question(self, user_id: int) -> bool:
        key = self._question_key(user_id)
        now = self._clock()
        cutoff = now - self._user_question_window_seconds
        await self._redis.zremrangebyscore(key, "-inf", cutoff)
        count = await self._redis.zcard(key)
        if count >= self._user_question_limit:
            return False
        await self._redis.zadd(key, {f"{now}:{uuid.uuid4().hex}": now})
        await self._redis.expire(key, int(self._user_question_window_seconds) + 1)
        return True

    async def _question_window_retry_after(self, user_id: int) -> float:
        key = self._question_key(user_id)
        oldest = await self._redis.zrange(key, 0, 0, withscores=True)
        if not oldest:
            return 0.0
        _, oldest_score = oldest[0]
        remaining = self._user_question_window_seconds - (self._clock() - oldest_score)
        return max(remaining, 0.0)

    @staticmethod
    def _user_key(user_id: int) -> str:
        return f"ratelimit:user:{user_id}:active"

    @staticmethod
    def _agent_key(agent_id: int) -> str:
        return f"ratelimit:agent:{agent_id}:active"

    @staticmethod
    def _question_key(user_id: int) -> str:
        return f"ratelimit:user:{user_id}:questions"
