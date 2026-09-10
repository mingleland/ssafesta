// @vitest-environment jsdom
// Unity 이벤트 → 오버레이 라우팅 (S15P21A604-601, GitLab #166).
//
// Mock 바가 흘리는 것과 Unity 가 보내는 것이 같은 경로를 타는지 본다 — 그 경로가 갈리면
// dev 에서 통과한 것이 실 Unity 에서 처음 실행되는 코드가 된다(MockInteractionBar 파일 주석).
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { initUnityBridge } from '../../../../unity/bridge/events';
import { initInteractionDispatcher } from '../../../interaction/dispatcher';
import { closeOverlay, getCurrentOverlay } from '../../../../shared/types/overlay';
import { getWorldScreen } from '../../../world/model/worldScreen';

let stop: () => void;

beforeEach(() => {
  initUnityBridge();
  stop = initInteractionDispatcher();
});

afterEach(() => {
  stop();
  closeOverlay();
});

function send(event: Record<string, unknown>) {
  window.FestaUnity?.onBoothInteract?.(JSON.stringify(event));
}

describe('WORLD_MINIGAME_INTERACT', () => {
  it('MINIGAME 오버레이를 열고 payload 를 그대로 싣는다', () => {
    send({ type: 'WORLD_MINIGAME_INTERACT', gameId: 'TIMER_STOP', machineId: 'lounge-timer-stop-01' });

    expect(getCurrentOverlay()).toEqual({
      type: 'MINIGAME',
      payload: { gameId: 'TIMER_STOP', machineId: 'lounge-timer-stop-01' },
    });
  });

  it('machineId 가 없어도 연다 — FE 가 해석하지 않는 값이라 필수가 아니다', () => {
    send({ type: 'WORLD_MINIGAME_INTERACT', gameId: 'TIMER_STOP' });

    expect(getCurrentOverlay()?.type).toBe('MINIGAME');
  });

  it('열리면 월드가 주인이 아니다 — 입력 잠금이 이 판정에 붙어 있다', () => {
    send({ type: 'WORLD_MINIGAME_INTERACT', gameId: 'TIMER_STOP' });
    expect(getWorldScreen()).toBe('visitor');

    closeOverlay();
    expect(getWorldScreen()).toBe('world');
  });

  it('모르는 type 은 조용히 무시한다 — 구버전 Unity 와의 전방 호환이 유지된다', () => {
    send({ type: 'WORLD_SOMETHING_NEW', gameId: 'X' });
    expect(getCurrentOverlay()).toBeNull();
  });
});
