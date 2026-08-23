// unity/bridge/events.ts 회귀 방어 — onBoothInteract는 JSON.parse 후 타입 단언만 하고 런타임
// validator/allowlist가 없다(필터링은 dispatcher.ts의 switch가 담당). 이 테스트는 그 전달 보장,
// 즉 신규 이벤트(BOOTH_GAME_INTERACT, #20)가 bridge를 손실 없이 통과함과 잘못된 JSON의 격리를 잠근다.
import { beforeEach, describe, expect, it } from 'vitest';
import { initUnityBridge, subscribeBoothInteract } from '../../events.ts';
import type { BoothInteractEvent } from '../../events.ts';

// vitest 환경이 'node'라 jsdom 없이는 window가 없다 — dispatcher.test.ts와 같은 최소 폴리필.
(globalThis as unknown as { window: typeof globalThis }).window ??= globalThis;

function emit(json: string) {
  initUnityBridge();
  window.FestaUnity!.onBoothInteract!(json);
}

describe('unity bridge — onBoothInteract', () => {
  beforeEach(() => {
    delete window.FestaUnity;
  });

  it('BOOTH_GAME_INTERACT를 4필드 손실 없이 구독자에게 전달한다', () => {
    const received: BoothInteractEvent[] = [];
    const unsubscribe = subscribeBoothInteract((event) => received.push(event));

    emit(JSON.stringify({ type: 'BOOTH_GAME_INTERACT', boothId: 7, objectId: 'game-npc-01', configId: 42 }));

    expect(received).toEqual([
      { type: 'BOOTH_GAME_INTERACT', boothId: 7, objectId: 'game-npc-01', configId: 42 },
    ]);
    unsubscribe();
  });

  it('잘못된 JSON은 구독자를 호출하지 않고 크래시하지 않는다(부스 하나의 오류가 전체를 막지 않음)', () => {
    const received: BoothInteractEvent[] = [];
    const unsubscribe = subscribeBoothInteract((event) => received.push(event));

    expect(() => emit('{not valid json')).not.toThrow();
    expect(received).toEqual([]);
    unsubscribe();
  });
});
