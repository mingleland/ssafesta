// 어느 렌더러를 쓸지 정하는 한 줄 — D-05 Spike (S15P21A604-470).
//
// Spike 기간 기본값은 R3F 다. 이 브랜치의 목적이 R3F 를 실제로 돌려 보는 것이기 때문이다.
// VITE_R3F_CANVAS=false 로 SVG 로 즉시 되돌린다 — A/B 비교용이자, Spike 가 FAIL 로 끝났을 때의
// 복귀 경로다. 그래서 TemporaryIsoRenderer 를 지우지 않는다.
//
// 값이 무엇이든 three 는 lazy chunk 에만 들어간다(BoothCanvasViewport 의 React.lazy).
// 이 플래그는 "무엇을 그릴까" 만 정하고 "무엇을 내려받을까" 는 정하지 않는다.
export const IS_R3F_CANVAS = import.meta.env.VITE_R3F_CANVAS !== 'false';

/**
 * 시각 검증 전용 모드 — **제품 동작이 아니다.**
 *
 * 캔버스는 `frameloop="demand"` 로 돌고, R3F 의 demand 는 rAF 로 프레임을 낸다. 그런데
 * 검증 하네스(숨겨진 브라우저 pane)에서는 `requestAnimationFrame` 이 **0회** 호출된다 —
 * 그 상태에서는 DOM 은 갱신되는데 3D 만 마지막 프레임에 얼어붙어, 스크린샷이 잔상을 보여 준다
 * (LJH T-50 이 그 사고다).
 *
 * 이 플래그가 켜지면 `frameloop="never"` + 타이머로 프레임을 직접 밀어 rAF 없이도 그린다.
 * 타이머는 rAF 와 달리 숨은 문서에서도 (느리게나마) 돈다.
 *
 *   기본·프로덕션    demand   (변경 없음)
 *   VITE_R3F_VISUAL_ACCEPTANCE=true + dev 빌드   never + 타이머
 */
export const IS_VISUAL_ACCEPTANCE =
  import.meta.env.DEV && import.meta.env.VITE_R3F_VISUAL_ACCEPTANCE === 'true';

/** 검증 모드의 프레임 간격(ms). 60fps 를 흉내 낼 이유가 없다 — 상태가 화면에 반영되면 된다 */
export const VISUAL_ACCEPTANCE_FRAME_MS = 100;
