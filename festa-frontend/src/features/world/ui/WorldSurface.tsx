// World Surface — Unity World Layer 자리의 교체 경계 (S15P21A604-406).
// 지금은 최신 Unity 인게임 캡처를 그대로 깔고, Persistent GameShell 단계에서 UnityHost 로 교체된다.
// 책임은 "풀스크린 비주얼" 하나뿐이다 — HUD·Overlay·입력 로직을 여기 넣지 않는다.
// 원본 SOURCE: docs/LJH/ui-design/00_context/sources/world-ingame-reference.png
import worldMockUrl from '../../../assets/festa/world/world-mock-background.png';
import './worldSurface.css';

/** 임시 정지 화면 — production Unity 대체가 아니다(월드 조작 불가) */
export function StaticMockWorldSurface() {
  return (
    <div className="world-surface" aria-hidden="true">
      <img className="world-surface-img" src={worldMockUrl} alt="" />
    </div>
  );
}
