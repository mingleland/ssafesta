// 어느 렌더러를 쓸지 정하는 한 줄 — D-05 Spike (S15P21A604-470).
//
// Spike 기간 기본값은 R3F 다. 이 브랜치의 목적이 R3F 를 실제로 돌려 보는 것이기 때문이다.
// VITE_R3F_CANVAS=false 로 SVG 로 즉시 되돌린다 — A/B 비교용이자, Spike 가 FAIL 로 끝났을 때의
// 복귀 경로다. 그래서 TemporaryIsoRenderer 를 지우지 않는다.
//
// 값이 무엇이든 three 는 lazy chunk 에만 들어간다(BoothCanvasViewport 의 React.lazy).
// 이 플래그는 "무엇을 그릴까" 만 정하고 "무엇을 내려받을까" 는 정하지 않는다.
export const IS_R3F_CANVAS = import.meta.env.VITE_R3F_CANVAS !== 'false';
