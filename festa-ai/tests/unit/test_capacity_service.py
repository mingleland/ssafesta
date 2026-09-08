"""S15P21A604-142 — 사용자/AI 직원/전체 동시 스트림 한도와 FIFO 대기열 (spec 008 FR-019/020).

전역 한도는 asyncio.Semaphore가 아니라 Redis 원자 연산으로 강제해야 다중 FastAPI
replica에서도 정확하다(research.md §2). 그래서 대부분의 테스트는 실제 프로세스
분리 없이도 "서로 다른 CapacityService 인스턴스가 같은 Redis를 공유하면 상태를
공유한다"는 형태로 그 요구사항을 검증한다.
"""

from __future__ import annotations

import asyncio

import fakeredis
import pytest

from app.services.capacity_service import CapacityExceeded, CapacityService


def _service(redis, **overrides: object) -> CapacityService:
    defaults: dict[str, object] = dict(
        redis=redis,
        user_concurrency_limit=1,
        user_question_limit=5,
        user_question_window_seconds=60.0,
        agent_concurrency_limit=5,
        global_concurrency_limit=20,
        queue_capacity=30,
        queue_wait_seconds=10.0,
        active_lease_ttl_seconds=90.0,
    )
    defaults.update(overrides)
    return CapacityService(**defaults)


@pytest.fixture
def redis():
    return fakeredis.FakeAsyncRedis()


@pytest.mark.parametrize(
    "field",
    [
        "user_concurrency_limit",
        "user_question_limit",
        "user_question_window_seconds",
        "agent_concurrency_limit",
        "global_concurrency_limit",
        "queue_capacity",
        "queue_wait_seconds",
        "active_lease_ttl_seconds",
    ],
)
def test_rejects_non_positive_settings(redis, field: str) -> None:
    with pytest.raises(ValueError):
        _service(redis, **{field: 0})


@pytest.mark.asyncio
async def test_second_concurrent_stream_for_same_user_is_rejected_without_queueing(redis) -> None:
    service = _service(redis)
    lease = await service.acquire(user_id=1, agent_id=10)

    with pytest.raises(CapacityExceeded):
        await service.acquire(user_id=1, agent_id=11)

    await lease.release()


@pytest.mark.asyncio
async def test_agent_concurrency_limit_is_rejected_without_queueing(redis) -> None:
    service = _service(redis, agent_concurrency_limit=2, user_concurrency_limit=100)
    leases = [await service.acquire(user_id=index, agent_id=99) for index in range(2)]

    with pytest.raises(CapacityExceeded):
        await service.acquire(user_id=999, agent_id=99)

    for lease in leases:
        await lease.release()


@pytest.mark.asyncio
async def test_user_question_window_limit_is_rejected_and_reports_precise_retry_after(
    redis,
) -> None:
    clock_value = 0.0

    def clock() -> float:
        return clock_value

    service = _service(
        redis,
        user_question_limit=2,
        user_question_window_seconds=60.0,
        user_concurrency_limit=100,
        clock=clock,
    )

    lease_one = await service.acquire(user_id=1, agent_id=1)
    await lease_one.release()
    clock_value = 10.0
    lease_two = await service.acquire(user_id=1, agent_id=1)
    await lease_two.release()

    clock_value = 20.0
    with pytest.raises(CapacityExceeded) as excinfo:
        await service.acquire(user_id=1, agent_id=1)

    # 첫 질문(t=0)이 60초 뒤(t=60)에 창을 벗어나므로 남은 대기시간은 40초다.
    assert excinfo.value.retry_after_seconds == pytest.approx(40.0)


@pytest.mark.asyncio
async def test_question_window_entries_older_than_window_do_not_count(redis) -> None:
    clock_value = 0.0

    def clock() -> float:
        return clock_value

    service = _service(
        redis,
        user_question_limit=1,
        user_question_window_seconds=60.0,
        user_concurrency_limit=100,
        clock=clock,
    )

    lease_one = await service.acquire(user_id=1, agent_id=1)
    await lease_one.release()

    clock_value = 61.0
    lease_two = await service.acquire(user_id=1, agent_id=1)
    await lease_two.release()


@pytest.mark.asyncio
async def test_global_limit_queues_and_succeeds_once_a_slot_frees(redis) -> None:
    service = _service(
        redis,
        global_concurrency_limit=1,
        queue_capacity=5,
        queue_wait_seconds=5.0,
        user_concurrency_limit=100,
        agent_concurrency_limit=100,
    )
    holder = await service.acquire(user_id=1, agent_id=1)

    waiter_task = asyncio.create_task(service.acquire(user_id=2, agent_id=2))
    await asyncio.sleep(0.05)
    assert not waiter_task.done()

    await holder.release()
    waiter_lease = await asyncio.wait_for(waiter_task, timeout=2.0)
    await waiter_lease.release()


# 완료 조건 "대기열 소진 순서 보장"(Jira 142)은 여기서 별도로 단위 테스트하지 않는다.
# 순서 보장은 CapacityService가 직접 구현하는 로직이 아니라 Redis BLPOP의 고유 계약
# ("여러 클라이언트가 같은 key를 기다리면 블록한 순서대로 깨어난다", Redis 공식 문서)에서
# 나온다. fakeredis(2.37)로 직접 검증해보면 이 계약을 재현하지 않고 오히려 LIFO로
# 깨운다(3-waiter 실측: 기대 [1,2,3], 실측 [3,2,1]) — 그래서 fakeredis 기준 테스트를
# 작성하면 실제 운영 동작과 반대인 순서를 "정답"으로 고정하게 된다. 이 한계는
# docs/25_트러블슈팅.md에 기록했다.


@pytest.mark.asyncio
async def test_global_queue_capacity_full_is_rejected_immediately(redis) -> None:
    service = _service(
        redis,
        global_concurrency_limit=1,
        queue_capacity=1,
        queue_wait_seconds=5.0,
        user_concurrency_limit=100,
        agent_concurrency_limit=100,
    )
    holder = await service.acquire(user_id=1, agent_id=1)
    first_waiter = asyncio.create_task(service.acquire(user_id=2, agent_id=2))
    await asyncio.sleep(0.05)

    with pytest.raises(CapacityExceeded):
        await service.acquire(user_id=3, agent_id=3)

    await holder.release()
    lease = await asyncio.wait_for(first_waiter, timeout=2.0)
    await lease.release()


@pytest.mark.asyncio
async def test_global_wait_times_out_and_is_rejected(redis) -> None:
    service = _service(
        redis,
        global_concurrency_limit=1,
        queue_capacity=5,
        queue_wait_seconds=0.1,
        user_concurrency_limit=100,
        agent_concurrency_limit=100,
    )
    holder = await service.acquire(user_id=1, agent_id=1)

    with pytest.raises(CapacityExceeded) as excinfo:
        await service.acquire(user_id=2, agent_id=2)

    assert excinfo.value.retry_after_seconds == 0.1
    await holder.release()


@pytest.mark.asyncio
async def test_release_frees_slots_for_reuse(redis) -> None:
    service = _service(
        redis, user_concurrency_limit=1, agent_concurrency_limit=1, global_concurrency_limit=1
    )
    lease = await service.acquire(user_id=1, agent_id=1)
    await lease.release()

    reacquired = await service.acquire(user_id=1, agent_id=1)
    await reacquired.release()


@pytest.mark.asyncio
async def test_release_is_idempotent(redis) -> None:
    service = _service(
        redis, user_concurrency_limit=1, agent_concurrency_limit=1, global_concurrency_limit=1
    )
    lease = await service.acquire(user_id=1, agent_id=1)
    await lease.release()
    await lease.release()

    reacquired = await service.acquire(user_id=1, agent_id=1)
    await reacquired.release()


@pytest.mark.asyncio
async def test_separate_service_instances_sharing_redis_enforce_one_global_limit(redis) -> None:
    """다중 FastAPI replica를 흉내낸다 — 각기 다른 인스턴스가 같은 Redis를 공유한다."""

    replica_a = _service(
        redis, global_concurrency_limit=1, user_concurrency_limit=100, agent_concurrency_limit=100
    )
    replica_b = _service(
        redis,
        global_concurrency_limit=1,
        user_concurrency_limit=100,
        agent_concurrency_limit=100,
        queue_wait_seconds=0.1,
    )

    lease_a = await replica_a.acquire(user_id=1, agent_id=1)

    with pytest.raises(CapacityExceeded):
        await replica_b.acquire(user_id=2, agent_id=2)

    await lease_a.release()
