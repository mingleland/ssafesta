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
    expect(getWorldUiState()).toEqual({ focus: false, minigame: false, avatar: false });
    expect(hasUnityModal()).toBe(false);
  });

  it('snapshot 은 변화가 없으면 같은 참조다 — useSyncExternalStore 가 무한 루프에 빠지지 않는다', () => {
    expect(getWorldUiState()).toBe(getWorldUiState());
  });
});

// Unity 는 `JsonUtility` 가 아니라 **문자열 연결**로 payload 를 만든다.
//   WorldUiBridge.cs:67
//   "{\"focus\":" + (focus ? "true" : "false") + ",\"minigame\":" + (minigame ? "true" : "false") + "}"
// 그래서 나올 수 있는 문자열이 정확히 넷이고, 여기서 그 넷을 그대로 잠근다. 필드 순서·공백·따옴표가
// 바뀌면 이 테스트가 먼저 깨진다 — 다른 파트가 직렬화를 손봤을 때 브라우저까지 가서 알게 되지 않도록.
describe('Unity 실제 payload (WorldUiBridge.cs 문자열 그대로)', () => {
  it.each([
    ['{"focus":false,"minigame":false}', { focus: false, minigame: false, avatar: false }],
    ['{"focus":true,"minigame":false}', { focus: true, minigame: false, avatar: false }],
    ['{"focus":false,"minigame":true}', { focus: false, minigame: true, avatar: false }],
    ['{"focus":true,"minigame":true}', { focus: true, minigame: true, avatar: false }],
    // avatar 가 추가된 payload (S15P21A604-820, GitLab #197)
    ['{"focus":false,"minigame":false,"avatar":true}', { focus: false, minigame: false, avatar: true }],
  ])('%s', (json, expected) => {
    // 초기값과 같은 조합도 실제로 반영됐는지 보려면 먼저 반대로 밀어 둔다.
    applyWorldUiStateJson('{"focus":true,"minigame":true}');
    __resetWorldUiStateForTests();
    applyWorldUiStateJson(json);
    expect(getWorldUiState()).toEqual(expected);
  });

  it('Unity 오브젝트·메서드 이름이 계약과 같다 — SendMessage 대상이 어긋나면 조용히 아무 일도 안 일어난다', async () => {
    const { WORLD_UI_BRIDGE_OBJECT, requestExitWorldUi } = await import('../../../host/worldUiBridge');
    const SendMessage = vi.fn();

    requestExitWorldUi({ SendMessage } as never, 'esc');

    expect(WORLD_UI_BRIDGE_OBJECT).toBe('WorldUiBridge');
    expect(SendMessage).toHaveBeenCalledWith('WorldUiBridge', 'RequestExitWorldUi', 'esc');
  });
});

describe('수신', () => {
  it('focus 를 받으면 Unity 모달이 있다고 판정한다', () => {
    applyWorldUiStateJson('{"focus":true,"minigame":false}');
    expect(getWorldUiState()).toEqual({ focus: true, minigame: false, avatar: false });
    expect(hasUnityModal()).toBe(true);
  });

  it('minigame 은 focus 로 유도되지 않는다 — 초점 없이 열리는 HUD 가 있다', () => {
    applyWorldUiStateJson('{"focus":false,"minigame":true}');
    expect(hasUnityModal()).toBe(true);
  });

  it('빠진 필드는 직전 값을 유지한다 — 부분 갱신이 나머지를 지우지 않는다', () => {
    applyWorldUiStateJson('{"focus":true,"minigame":true}');
    applyWorldUiStateJson('{"focus":false}');
    expect(getWorldUiState()).toEqual({ focus: false, minigame: true, avatar: false });
  });

  it('모르는 필드는 무시한다 — 필드 추가가 FE 배포를 강제하지 않는다', () => {
    applyWorldUiStateJson('{"focus":true,"dialogue":true}');
    expect(getWorldUiState()).toEqual({ focus: true, minigame: false, avatar: false });
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
    expect(getWorldUiState()).toEqual({ focus: false, minigame: false, avatar: false });
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
    expect(getWorldUiState()).toEqual({ focus: false, minigame: true, avatar: false });
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

// 월드 안 아바타 커스터마이징 (S15P21A604-820, GitLab #197).
//
// 이 필드가 없으면 Unity 가 avatar:true 를 보내도 readFlag 가 조용히 버려서 hasUnityModal() 이
// false 다 — ESC 가 아바타 화면 위에 GameMenu 를 연다. #132 가 focus·minigame 으로 고쳤던 결함이
// 세 번째 화면에서 그대로 재발하는 자리다.
describe('아바타 커스터마이징 (S15P21A604-820)', () => {
  it('avatar 를 받으면 Unity 모달이 있다고 판정한다 — ESC 2단계가 선다', () => {
    applyWorldUiStateJson('{"focus":false,"minigame":false,"avatar":true}');
    expect(getWorldUiState()).toEqual({ focus: false, minigame: false, avatar: true });
    expect(hasUnityModal()).toBe(true);
  });

  it('avatar 만 온 부분 갱신이 focus·minigame 을 지우지 않는다', () => {
    applyWorldUiStateJson('{"focus":true,"minigame":true}');
    applyWorldUiStateJson('{"avatar":true}');
    expect(getWorldUiState()).toEqual({ focus: true, minigame: true, avatar: true });
  });

  it('avatar 가 boolean 이 아니면 그 필드만 버리고 로그로 드러낸다', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    applyWorldUiStateJson('{"minigame":true,"avatar":"open"}');
    expect(getWorldUiState()).toEqual({ focus: false, minigame: true, avatar: false });
    expect(error).toHaveBeenCalled();
  });

  it('avatar 만 떠 있을 때 닫히면 모달이 없다고 판정한다', () => {
    applyWorldUiStateJson('{"avatar":true}');
    applyWorldUiStateJson('{"avatar":false}');
    expect(hasUnityModal()).toBe(false);
  });

  it('인스턴스가 새로 서면 avatar 도 비운다', () => {
    applyWorldUiStateJson('{"avatar":true}');
    resetWorldUiState();
    expect(getWorldUiState()).toEqual({ focus: false, minigame: false, avatar: false });
  });

  it('여는 명령의 오브젝트·메서드·인자가 계약과 같다 — 어긋나면 Unity 가 조용히 무시한다', async () => {
    const { WORLD_UI_BRIDGE_OBJECT, requestAvatarCustomization } = await import('../../../host/worldUiBridge');
    const SendMessage = vi.fn();

    requestAvatarCustomization({ SendMessage } as never);

    expect(WORLD_UI_BRIDGE_OBJECT).toBe('WorldUiBridge');
    expect(SendMessage).toHaveBeenCalledTimes(1);
    expect(SendMessage).toHaveBeenCalledWith('WorldUiBridge', 'RequestAvatarCustomization', 'esc-menu');
  });
});
