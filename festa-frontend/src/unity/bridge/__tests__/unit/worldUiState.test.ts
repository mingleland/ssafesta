// Unity 모달 관측값 (S15P21A604-450, GitLab #132).
//
// 잠그는 것 넷.
//   ① 신호가 오기 전에도 동작한다 — Unity 가 이 채널을 아직 안 보내면 예전과 똑같다
//   ② 계약 밖 payload 가 나머지를 넘어뜨리지 않는다 (onWorldConnectionState 와 같은 방어 수준)
//   ③ 같은 상태를 다시 받으면 구독자를 깨우지 않는다 — 매 frame push 가 와도 재렌더가 없다
//   ④ 인스턴스가 새로 서면 비운다 — 옛 focus=true 가 남으면 ESC 가 닫을 수 없는 것을 향해 나간다
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetWorldUiStateForTests,
  applyWorldUiStateJson,
  getWorldUiState,
  hasUnityModal,
  resetWorldUiState,
  subscribeWorldUiState,
} from '../../worldUiState';

beforeEach(() => {
  __resetWorldUiStateForTests();
});
afterEach(() => {
  __resetWorldUiStateForTests();
  vi.restoreAllMocks();
});

describe('초기값', () => {
  it('신호가 오기 전에는 아무 모달도 없다', () => {
    expect(getWorldUiState()).toEqual({ focus: false, minigame: false });
    expect(hasUnityModal()).toBe(false);
  });

  it('snapshot 은 변화가 없으면 같은 참조다 — useSyncExternalStore 가 무한 루프에 빠지지 않는다', () => {
    expect(getWorldUiState()).toBe(getWorldUiState());
  });
});

describe('수신', () => {
  it('focus 를 받으면 Unity 모달이 있다고 판정한다', () => {
    applyWorldUiStateJson('{"focus":true,"minigame":false}');
    expect(getWorldUiState()).toEqual({ focus: true, minigame: false });
    expect(hasUnityModal()).toBe(true);
  });

  it('minigame 은 focus 로 유도되지 않는다 — 초점 없이 열리는 HUD 가 있다', () => {
    applyWorldUiStateJson('{"focus":false,"minigame":true}');
    expect(hasUnityModal()).toBe(true);
  });

  it('빠진 필드는 직전 값을 유지한다 — 부분 갱신이 나머지를 지우지 않는다', () => {
    applyWorldUiStateJson('{"focus":true,"minigame":true}');
    applyWorldUiStateJson('{"focus":false}');
    expect(getWorldUiState()).toEqual({ focus: false, minigame: true });
  });

  it('모르는 필드는 무시한다 — 필드 추가가 FE 배포를 강제하지 않는다', () => {
    applyWorldUiStateJson('{"focus":true,"dialogue":true}');
    expect(getWorldUiState()).toEqual({ focus: true, minigame: false });
  });

  it('같은 상태를 다시 받으면 구독자를 깨우지 않는다', () => {
    const listener = vi.fn();
    subscribeWorldUiState(listener);

    applyWorldUiStateJson('{"focus":true,"minigame":false}');
    applyWorldUiStateJson('{"focus":true,"minigame":false}');

    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe('계약 밖 payload', () => {
  it('JSON 이 아니면 상태를 바꾸지 않고 로그로 드러낸다', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    applyWorldUiStateJson('focus=true');
    expect(getWorldUiState()).toEqual({ focus: false, minigame: false });
    expect(error).toHaveBeenCalled();
  });

  it('객체가 아니면 무시한다', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    applyWorldUiStateJson('"true"');
    expect(hasUnityModal()).toBe(false);
  });

  it('boolean 이 아닌 필드는 그 필드만 버리고 나머지는 살린다', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    applyWorldUiStateJson('{"focus":"yes","minigame":true}');
    expect(getWorldUiState()).toEqual({ focus: false, minigame: true });
    expect(error).toHaveBeenCalled();
  });
});

describe('reset', () => {
  it('인스턴스가 새로 서면 비운다', () => {
    applyWorldUiStateJson('{"focus":true,"minigame":true}');
    resetWorldUiState();
    expect(hasUnityModal()).toBe(false);
  });

  it('이미 비어 있으면 구독자를 깨우지 않는다', () => {
    const listener = vi.fn();
    subscribeWorldUiState(listener);
    resetWorldUiState();
    expect(listener).not.toHaveBeenCalled();
  });
});
