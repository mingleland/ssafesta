// S15P21A604-363 — tick 엔진을 고정 setInterval 카운트에서 performance.now() 기반
// 경과-실시간 누적기로 바꾼 핵심 계산. 브라우저가 탭이 백그라운드/비활성일 때 setInterval
// 발화 자체를 초당 1회 수준으로 늦춰도, 콜백이 실제로 불렸을 때 "지난 실시간"을 재서 밀린
// 만큼의 논리 tick을 몰아 처리하면 tick 하나의 실제 길이가 항상 REFERENCE_TICK_MS에
// 가깝게 유지된다 — 그 위에 얹힌 무적시간(틱 수 기준) 계산도 다시 정확해진다.
//
// 이 계산 자체를 ReferenceGamePlayer의 tick 이펙트 안에 두면 테스트가 실제 setInterval
// 발화 간격에 종속된다(vi.useFakeTimers()로 시계를 벌크로 당겨도 그사이 예정된 모든 tick이
// 충실히 각각 발화해버려 "콜백이 늦게 불렸다"는 상황 자체를 못 만든다) — 그래서 순수
// 함수로 뽑아 여기서 독립적으로 검증한다. 실제 이펙트 배선은
// referenceGamePlayerHeldKeyMovement.test.tsx가, 그 위에서 도는 이동/틱 로직은
// movePlayerFromHeldKeys.test.ts(2-트랩 시나리오)가 검증한다.
import { describe, expect, it } from 'vitest';
import { MAX_CATCHUP_TICKS, planCatchUpTicks, REFERENCE_TICK_MS } from '../../runtime/reference/referenceRuntime.ts';

describe('planCatchUpTicks', () => {
  it('한 tick(REFERENCE_TICK_MS)도 안 지났으면 아무것도 처리하지 않는다', () => {
    expect(planCatchUpTicks(0)).toEqual({ steps: 0, consumedMs: 0 });
    expect(planCatchUpTicks(REFERENCE_TICK_MS - 1)).toEqual({ steps: 0, consumedMs: 0 });
  });

  it('정상 범위에서는 정확히 그만큼의 tick을 처리하고, 나머지 단수는 버리지 않고 이월시킨다', () => {
    // 딱 1틱.
    expect(planCatchUpTicks(REFERENCE_TICK_MS)).toEqual({ steps: 1, consumedMs: REFERENCE_TICK_MS });
    // 5틱 분량(600ms)이 한 번에 밀렸어도(예: 스로틀링) 5틱을 몰아 처리한다.
    expect(planCatchUpTicks(600)).toEqual({ steps: 5, consumedMs: 600 });
    // 딱 떨어지지 않는 경우(650ms = 5틱 + 50ms) — consumedMs는 처리한 5틱분(600ms)만큼만
    // 반영해, 남는 50ms는 버리지 않고 다음 호출의 elapsedMs 계산에 자연히 이월되게 한다.
    expect(planCatchUpTicks(650)).toEqual({ steps: 5, consumedMs: 600 });
  });

  it('MAX_CATCHUP_TICKS를 넘게 밀렸으면 캡까지만 처리하고, 남는 밀린 시간은 버려 현재 시각으로 재동기화한다', () => {
    const justOverCap = (MAX_CATCHUP_TICKS + 1) * REFERENCE_TICK_MS;
    const plan = planCatchUpTicks(justOverCap);
    expect(plan.steps).toBe(MAX_CATCHUP_TICKS);
    // 캡을 넘긴 경우엔 처리한 캡만큼(steps*TICK_MS)이 아니라 elapsedMs 전체를 소비한 것으로
    // 쳐서(consumedMs === elapsedMs) 남은 밀린 시간을 버린다 — 스포너 대량 생성 등 부작용 방지.
    expect(plan.consumedMs).toBe(justOverCap);

    // 아주 오래(5분) 비활성이었어도 여전히 캡까지만 처리한다 — 폭주하지 않는다.
    const fiveMinutes = 5 * 60 * 1000;
    const longGapPlan = planCatchUpTicks(fiveMinutes);
    expect(longGapPlan.steps).toBe(MAX_CATCHUP_TICKS);
    expect(longGapPlan.consumedMs).toBe(fiveMinutes);
  });

  it('정확히 캡만큼 밀린 경우(경계값)는 캡을 초과한 것으로 취급하지 않고 정상 경로로 처리한다', () => {
    const exactlyAtCap = MAX_CATCHUP_TICKS * REFERENCE_TICK_MS;
    // 정상 경로이므로 consumedMs는 steps*TICK_MS(=elapsedMs 전체와 우연히 같음)이지,
    // "캡을 넘겨서 버렸다"는 별도 분기를 탄 게 아니다 — 다음 케이스(경계+1ms)와 대조해 확인한다.
    expect(planCatchUpTicks(exactlyAtCap)).toEqual({ steps: MAX_CATCHUP_TICKS, consumedMs: exactlyAtCap });
    // 경계를 살짝 넘기면(1ms만 더) 아직 캡+1틱은 안 됐으므로 여전히 정상 경로 — steps는 그대로.
    expect(planCatchUpTicks(exactlyAtCap + 1)).toEqual({ steps: MAX_CATCHUP_TICKS, consumedMs: exactlyAtCap });
  });

  it('커스텀 tickMs/maxSteps 인자도 그대로 반영한다', () => {
    expect(planCatchUpTicks(250, 100, 2)).toEqual({ steps: 2, consumedMs: 200 });
    expect(planCatchUpTicks(1000, 100, 2)).toEqual({ steps: 2, consumedMs: 1000 }); // 캡(2*100=200) 초과 → 전체 소비.
  });
});
