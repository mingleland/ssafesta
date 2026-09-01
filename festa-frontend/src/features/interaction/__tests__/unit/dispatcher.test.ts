import { beforeEach, describe, expect, it } from 'vitest';
import { initInteractionDispatcher } from '../../dispatcher.ts';
import { closeOverlay, getCurrentOverlay } from '../../../../shared/types/overlay.ts';

// vitest 환경이 'node'라 jsdom 없이는 window가 없다 — 실 DOM은 필요 없고 initUnityBridge가
// FestaUnity 콜백을 걸 대상 객체 하나만 있으면 되므로 최소 폴리필로 대체한다(jsdom 의존성 추가 없음).
(globalThis as unknown as { window: typeof globalThis }).window ??= globalThis;

// events.ts의 onBoothInteract는 JSON.parse만 하고 type을 검증하지 않는다 — 브릿지 자체를
// 재구현하지 않고, window.FestaUnity.onBoothInteract가 실제로 하는 것과 같은 방식(initUnityBridge
// 호출 후 그 함수로 JSON 문자열을 흘려보냄)으로 이벤트를 주입한다.
import { initUnityBridge } from '../../../../unity/bridge/events.ts';

function emit(json: string) {
  initUnityBridge();
  window.FestaUnity!.onBoothInteract!(json);
}

describe('initInteractionDispatcher', () => {
  beforeEach(() => {
    closeOverlay();
  });

  it('BOOTH_LAPTOP_INTERACT를 LAPTOP 오버레이로 연다 — url 필드는 계약에서 제거됐다(-297, 정본은 booth 조회)', () => {
    const unsubscribe = initInteractionDispatcher();
    emit(JSON.stringify({ type: 'BOOTH_LAPTOP_INTERACT', boothId: 7, objectId: 'laptop-1' }));

    expect(getCurrentOverlay()).toEqual({ type: 'LAPTOP', payload: { boothId: 7, objectId: 'laptop-1' } });
    unsubscribe();
  });

  it('구버전 Unity가 url을 보내도 payload로 전달하지 않는다 — URL 정본은 GET /booths/{id}(016 C-01)', () => {
    const unsubscribe = initInteractionDispatcher();
    emit(JSON.stringify({ type: 'BOOTH_LAPTOP_INTERACT', boothId: 7, objectId: 'laptop-1', url: 'https://stale.example.com' }));

    expect(getCurrentOverlay()).toEqual({ type: 'LAPTOP', payload: { boothId: 7, objectId: 'laptop-1' } });
    unsubscribe();
  });

  it('BOOTH_PROJECT_INTERACT를 PROJECT 오버레이로 연다 — 계약 #110 note 2754197 (boothId·objectId, configId 없음)', () => {
    const unsubscribe = initInteractionDispatcher();
    emit(JSON.stringify({ type: 'BOOTH_PROJECT_INTERACT', boothId: 7, objectId: 'project-panel-1' }));

    expect(getCurrentOverlay()).toEqual({ type: 'PROJECT', payload: { boothId: 7, objectId: 'project-panel-1' } });
    unsubscribe();
  });

  it('AI_AGENT_INTERACT의 configId를 agentId로 바꿔 AI_CHAT을 연다', () => {
    const unsubscribe = initInteractionDispatcher();
    emit(JSON.stringify({ type: 'AI_AGENT_INTERACT', boothId: 7, objectId: 'ai-1', configId: 42 }));

    expect(getCurrentOverlay()).toEqual({ type: 'AI_CHAT', payload: { boothId: 7, agentId: 42 } });
    unsubscribe();
  });

  it('BOOTH_GAME_INTERACT는 configId를 그대로 실어 GAME 오버레이를 연다(#20 — 이름 변환 없음)', () => {
    const unsubscribe = initInteractionDispatcher();
    emit(JSON.stringify({ type: 'BOOTH_GAME_INTERACT', boothId: 7, objectId: 'game-npc-01', configId: 42 }));

    expect(getCurrentOverlay()).toEqual({
      type: 'GAME',
      payload: { boothId: 7, objectId: 'game-npc-01', configId: 42 },
    });
    unsubscribe();
  });

  it('미지 type은 무시한다 — 서버가 신설한 이벤트에도 크래시하지 않는다(전방 호환)', () => {
    const unsubscribe = initInteractionDispatcher();
    emit(JSON.stringify({ type: 'UNKNOWN_FUTURE_EVENT', boothId: 7 }));

    expect(getCurrentOverlay()).toBeNull();
    unsubscribe();
  });

  it('unsubscribe 후에는 이벤트가 와도 오버레이를 열지 않는다', () => {
    const unsubscribe = initInteractionDispatcher();
    unsubscribe();
    emit(JSON.stringify({ type: 'BOOTH_LAPTOP_INTERACT', boothId: 7, objectId: 'laptop-1', url: 'https://example.com' }));

    expect(getCurrentOverlay()).toBeNull();
  });
});
