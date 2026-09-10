// Unity 월드가 **라우트보다 오래 사는가** (S15P21A604-620).
//
// 지금까지 Unity 인스턴스의 수명은 `/app/world` 라우트의 수명이었다. 부스 스튜디오나 관리
// 상세로 나가면 `WorldPage` 가 언마운트되고 그 안의 `UnityHost` 도 함께 죽어 인스턴스가
// 파괴됐다 — 돌아올 때마다 부팅 + 로비 + main 씬 로드로 50~84초를 다시 기다린다(#128).
//
// **인스턴스 소유는 이미 라우트 독립적이었다.** `sessionManager` 가 모듈 스코프 싱글톤이고
// `getReadyUnityInstance()` 로 트리 밖에서도 인스턴스를 얻는다. 라우트에 묶여 있던 것은
// canvas DOM 노드와 "언마운트 = release" 배선 둘뿐이라, 이 모듈은 그 둘만 떼어 낸다.
//
// 값이 둘인 이유 — 서로 다른 질문이다:
//
//   mounted   Unity 를 띄워 둘 것인가        한 번 서면 명시적으로 내릴 때까지 유지
//   visible   지금 사용자에게 보일 것인가    월드 화면에 있는 동안만
//
// 합치면 "화면을 떠났으니 내린다" 가 되어 원래 문제로 돌아간다.
//
// `hostPhase`·`worldUiState`·`gameClientUi` 와 같은 module-level store 관례다.

export interface WorldMount {
  mounted: boolean;
  visible: boolean;
}

const DOWN: WorldMount = Object.freeze({ mounted: false, visible: false });

let state: WorldMount = DOWN;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(next: WorldMount): void {
  if (next.mounted === state.mounted && next.visible === state.visible) return;
  state = next;
  emit();
}

/** 월드 화면에 들어왔다 — 아직 없으면 띄우고, 있으면 그대로 다시 보여 준다 */
export function showWorld(): void {
  setState({ mounted: true, visible: true });
}

/**
 * 월드 화면을 떠났다. **내리지 않는다** — 이 함수가 하는 일은 감추는 것뿐이다.
 * 부스 스튜디오·관리 상세로 나갔다 돌아오는 것이 정상 동선이고, 그때마다 다시 부팅하지
 * 않는 것이 이 모듈의 존재 이유다.
 */
export function hideWorld(): void {
  if (!state.mounted) return;
  setState({ mounted: true, visible: false });
}

/**
 * 실제로 내린다. 부르는 자리는 **월드로 돌아올 일이 없어진 때**뿐이다 — 로그아웃,
 * 그리고 가드가 로그인 화면으로 돌려보낼 때. 라우트 이동으로는 부르지 않는다.
 */
export function unmountWorld(): void {
  setState(DOWN);
}

export function subscribeWorldMount(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getWorldMount(): WorldMount {
  return state;
}

// 테스트 전용
export function __resetWorldMountForTests(): void {
  state = DOWN;
  listeners.clear();
}
