// 월드 컨텍스트 — "지금 플레이어가 부스 안인가" (S15P21A604-627, GitLab #174).
//
// **정본은 Unity 다.** 여기 있는 것은 복제된 관측값이고 판정 권한이 아니다. FE 가 가진 값
// 어느 것도 "부스 안" 을 뜻하지 않는다 — 초점 카메라는 밖에서도 켜지고, 상호작용 이력은
// 위치가 아니며, 입력 잠금은 FE 자신의 상태다. 그래서 추정하지 않고 받은 값만 쓴다.
//
// **`WorldUiState` 에 합치지 않는다.** 그쪽 `hasUnityModal()` 은 `focus || minigame` 인데,
// 거기에 `insideBooth` 를 넣으면 부스 안에 있다는 이유만으로 "Unity 모달이 떠 있다" 로 오판해
// ESC 가 GameMenu 를 못 여는 회귀가 즉시 난다. `focus`·`minigame` 은 **UI 모달 상태**,
// `insideBooth` 는 **월드 컨텍스트** 라 층이 다르다(#174 에서 게임 파트도 동의했다).
//
// 이 모듈이 `unity/bridge/` 가 아니라 여기 있는 이유: 이 값은 `onBoothInteract` 이벤트 채널로
// 와서 **dispatcher 를 거친다**. dispatcher 는 이미 `worldScreen`·`gameClientUi` 를 부르고,
// 반대로 `unity/bridge/` 를 부르면 레이어 방향이 역행한다. `worldUiState` 는 전용 콜백을 직접
// 받으므로 bridge 에 있는 것이 맞다 — 경로가 다르다.
import { useSyncExternalStore } from 'react';

export interface WorldContext {
  /** 부스 안에 있는가 — 나가기 버튼의 유일한 노출 조건 */
  insideBooth: boolean;
  /** 어느 부스인가. 밖이면 null. 화면 분기에 쓰지 않는다 — 로그·분석용이다 */
  boothId: number | null;
}

// 신호가 오기 전의 값. Unity 가 이 이벤트를 아직 안 보내도 FE 는 예전과 똑같이 동작한다.
const OUTSIDE: WorldContext = Object.freeze({ insideBooth: false, boothId: null });

let state: WorldContext = OUTSIDE;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

/**
 * Unity 가 보낸 컨텍스트를 담는다 — dispatcher 만 부른다.
 *
 * 밖일 때 `boothId` 가 오지 않는 것이 계약이라, 안에 있을 때만 번호를 남긴다. 밖인데 번호가
 * 남아 있으면 "방금 나온 부스" 와 "지금 있는 부스" 가 구분되지 않는다.
 */
export function applyBoothContext(insideBooth: boolean, boothId?: number): void {
  const nextBoothId = insideBooth && typeof boothId === 'number' ? boothId : null;
  if (state.insideBooth === insideBooth && state.boothId === nextBoothId) return;
  state = { insideBooth, boothId: nextBoothId };
  emit();
}

/**
 * 새 Unity 인스턴스에는 컨텍스트가 없다 — `UnityHost` 가 boot 마다 부른다.
 * 옛 관측값이 남으면 부스 밖인데 나가기 버튼이 유령으로 떠 있게 된다.
 */
export function resetWorldContext(): void {
  if (state === OUTSIDE) return;
  state = OUTSIDE;
  emit();
}

export function subscribeWorldContext(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** useSyncExternalStore 의 snapshot getter — 변화가 없으면 같은 참조를 돌려준다 */
export function getWorldContext(): WorldContext {
  return state;
}

export function useWorldContext(): WorldContext {
  return useSyncExternalStore(subscribeWorldContext, getWorldContext, getWorldContext);
}

// 테스트 전용
export function __resetWorldContextForTests(): void {
  state = OUTSIDE;
  listeners.clear();
}
