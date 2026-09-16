// Unity 가 쥐고 있는 모달 상태의 **관측값** (S15P21A604-450, GitLab #132).
//
// 왜 필요한가: ESC 판정의 입력값에 Unity 쪽이 들어갈 자리가 없었다. `worldScreen` 은 FE store
// 둘(Overlay Bus·gameClientUi)만 보므로, Unity 가 초점 카메라나 미니게임 HUD 를 쥐고 있어도
// FE 에게는 `'world'` 로 보이고 ESC 가 규칙대로 GameMenu 를 열었다 — 줌은 풀렸는데 설정 창이
// 같이 뜨는 현상이 그것이다. 초점 전용 문제가 아니다: `MinigameInteractable` → `TimerStopGameHud`
// 도 FE 오버레이 없이 열리므로 같은 결함이 난다.
//
// **정본은 Unity 다.** 여기 있는 것은 복제된 관측값이고 판정 권한이 아니다. 그래서 이 모듈은
// 상태를 만들지 않는다 — Unity 가 전이 시에만 밀어 주는 것을 받아 두기만 한다.
//
// **상태와 명령을 분리한다.** `focus` 가 false 가 됐다고 FE 가 제 오버레이를 닫지 않는다. 닫는
// 것은 `RequestExitWorldUi` 명령으로만 요청한다(host/worldUiBridge.ts). 2026-09-08 슬롯머신
// 사고가 정확히 "상태가 false 가 됐으니 남의 기능도 닫는다" 였다 — `InputBridge` 주석 참조.
//
// 입력 잠금(`InputBridge`)은 건드리지 않는다. 그쪽은 owner-set 이 정본이고 이 채널과 무관하다.

/** Unity 가 쥔 모달 상태. 필드는 **실제 충돌이 확인된 것만** 둔다(#132 note). */
export interface WorldUiState {
  /** 초점 카메라(줌) 활성 — `InteractionFocusCamera.IsFocused` */
  focus: boolean;
  /** Unity 자체 미니게임 HUD 활성 — `TimerStopGameHud`. 초점 없이도 열려 `focus` 로 유도되지 않는다 */
  minigame: boolean;
  /**
   * 월드 안 아바타 커스터마이징 화면 활성 (S15P21A604-820, GitLab #197).
   *
   * `CharacterLobby` 씬을 additive 로 얹어 여는 것이라 월드·NGO·Player 는 그대로 살아 있다.
   * FE 에게는 `'world'` 로 보이므로, 이 필드가 없으면 ESC 가 아바타 화면 위에 GameMenu 를
   * 연다 — `focus`·`minigame` 을 여기 둔 것과 같은 이유다.
   */
  avatar: boolean;
}

// 신호가 오기 전의 값. Unity 가 이 채널을 아직 안 보내도 FE 는 예전과 똑같이 동작한다.
const NONE: WorldUiState = Object.freeze({ focus: false, minigame: false, avatar: false });

let state: WorldUiState = NONE;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

/** useSyncExternalStore 의 snapshot getter — 변화가 없으면 같은 참조를 돌려준다. */
export function getWorldUiState(): WorldUiState {
  return state;
}

export function subscribeWorldUiState(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Unity 모달이 하나라도 떠 있는가 — ESC 중재가 쓰는 유일한 판정. */
export function hasUnityModal(snapshot: WorldUiState = state): boolean {
  return snapshot.focus || snapshot.minigame || snapshot.avatar;
}

/**
 * Unity 인스턴스가 새로 서면 관측값을 비운다.
 *
 * 새 인스턴스에는 모달이 없다. 비우지 않으면 재시도 boot 뒤에도 옛 `focus: true` 가 남아
 * ESC 가 아무 데도 가지 않는다(닫을 Unity 모달이 있다고 믿고 명령만 보낸다).
 */
export function resetWorldUiState(): void {
  if (state === NONE) return;
  state = NONE;
  emit();
}

/**
 * Unity → FE 수신부. 계약 밖 값은 **무시하고 로그로 드러낸다** — `onWorldConnectionState` 와 같은
 * 방어 수준이다(T-24 정신). 모르는 필드가 더 와도 무시한다: 필드 추가가 FE 배포를 강제하지 않는다.
 */
export function applyWorldUiStateJson(json: string): void {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch (err) {
    console.error('[unity-bridge] onWorldUiState JSON 파싱 실패', json, err);
    return;
  }
  if (typeof parsed !== 'object' || parsed === null) {
    console.error('[unity-bridge] onWorldUiState payload 가 객체가 아니다', json);
    return;
  }

  const raw = parsed as Partial<Record<keyof WorldUiState, unknown>>;
  const next: WorldUiState = {
    focus: readFlag(raw.focus, state.focus, 'focus'),
    minigame: readFlag(raw.minigame, state.minigame, 'minigame'),
    avatar: readFlag(raw.avatar, state.avatar, 'avatar'),
  };

  if (next.focus === state.focus && next.minigame === state.minigame && next.avatar === state.avatar) {
    return;
  }
  state = next;
  emit();
}

// 빠진 필드는 **직전 값을 유지한다** — Unity 가 부분 갱신을 보내도 되고, 잘못된 타입 하나가
// 나머지 필드를 넘어뜨리지 않는다. 조용히 넘기지 않고 어떤 값이 왔는지 남긴다.
function readFlag(value: unknown, fallback: boolean, field: string): boolean {
  if (typeof value === 'boolean') return value;
  if (value === undefined) return fallback;
  console.error(`[unity-bridge] onWorldUiState.${field} 가 boolean 이 아니다`, value);
  return fallback;
}

/** 테스트 전용 — 모듈 상태를 초기화한다. */
export function __resetWorldUiStateForTests(): void {
  state = NONE;
  listeners.clear();
}
