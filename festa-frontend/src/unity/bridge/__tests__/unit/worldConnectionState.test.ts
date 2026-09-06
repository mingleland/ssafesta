// unity/bridge/events.ts — 월드 접속 상태 채널 (S15P21A604-432, GitLab #131).
// Unity WorldReconnector 가 밀어 주는 state/detail 이 손실 없이 구독자에게 닿는지, 계약 밖 값이
// 격리되는지, 구독 해제가 실제로 끊는지를 잠근다. 재시도 로직 자체는 Unity 소유라 여기서 다루지 않는다.
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { initUnityBridge, subscribeWorldConnectionState } from '../../events.ts';
import type { WorldConnectionState } from '../../events.ts';

(globalThis as unknown as { window: typeof globalThis }).window ??= globalThis;

function emit(state: string, detail: string) {
  initUnityBridge();
  window.FestaUnity!.onWorldConnectionState!(state, detail);
}

describe('unity bridge — onWorldConnectionState', () => {
  beforeEach(() => {
    delete window.FestaUnity;
  });

  it('4개 상태를 detail 과 함께 그대로 전달한다', () => {
    const received: Array<[WorldConnectionState, string]> = [];
    const unsubscribe = subscribeWorldConnectionState((state, detail) => received.push([state, detail]));

    emit('disconnected', 'REPLACED_BY_SAME_USER');
    emit('reconnecting', '2');
    emit('connected', '');
    emit('failed', 'WORLD_SESSION_UNAVAILABLE');

    expect(received).toEqual([
      ['disconnected', 'REPLACED_BY_SAME_USER'],
      ['reconnecting', '2'],
      ['connected', ''],
      ['failed', 'WORLD_SESSION_UNAVAILABLE'],
    ]);
    unsubscribe();
  });

  it('계약 밖 state 는 전달하지 않고 오류로 드러낸다 — 조용히 삼키지 않는다(T-24)', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const received: string[] = [];
    const unsubscribe = subscribeWorldConnectionState((state) => received.push(state));

    emit('reconnected', '');

    expect(received).toEqual([]);
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
    unsubscribe();
  });

  it('구독자 하나가 던져도 나머지 전달과 Unity 콜백을 막지 않는다', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const received: string[] = [];
    const unsubscribeBad = subscribeWorldConnectionState(() => {
      throw new Error('boom');
    });
    const unsubscribeGood = subscribeWorldConnectionState((state) => received.push(state));

    expect(() => emit('reconnecting', '1')).not.toThrow();

    expect(received).toEqual(['reconnecting']);
    spy.mockRestore();
    unsubscribeBad();
    unsubscribeGood();
  });

  it('구독 해제 뒤에는 더 이상 받지 않는다', () => {
    const received: string[] = [];
    const unsubscribe = subscribeWorldConnectionState((state) => received.push(state));

    emit('reconnecting', '1');
    unsubscribe();
    emit('failed', 'INVALID_TOKEN');

    expect(received).toEqual(['reconnecting']);
  });
});
