// Mock Interaction Bar — **DEV_ONLY**. Unity F 상호작용의 개발용 대역 (S15P21A604-406).
//
// 제품 HUD 가 아니다(D-08 · hud-decisions: 기능 Launcher 금지). Unity 송신부가 없는 환경에서
// 상호작용 경로를 손으로 검증하기 위한 장치다. 노출 조건은 `IS_DEV_INTERACTION_BAR` 하나이며
// dev 빌드 + 명시적 플래그를 동시에 요구한다 — 프로덕션 번들에서는 조건이 상수 false 라
// 트리셰이킹으로 사라진다.
//
// **Unity 가 보내는 것과 똑같은 JSON 을 브리지에 흘린다** (S15P21A604-343·-414·-415).
// 전에는 `openOverlay` 를 직접 불렀는데, 그러면 계약의 절반(events.ts 파싱 → dispatcher 라우팅)을
// 건너뛰어 정작 Unity 를 붙였을 때 처음 실행되는 코드가 검증되지 않은 채로 남는다.
// 이제 이 바를 누르는 것과 Unity 가 F 를 보내는 것이 같은 경로를 탄다.
import { useEffect } from 'react';
import { initUnityBridge } from '../../../unity/bridge/events';
import './mockInteractionBar.css';

const MOCK_BOOTH_ID = 1;

/** Unity 가 실제로 보내는 payload 그대로 — 여기서 새 필드를 만들지 않는다 */
const ENTRIES: { label: string; event: Record<string, unknown> }[] = [
  { label: '프로젝트 전시', event: { type: 'BOOTH_PROJECT_INTERACT', boothId: MOCK_BOOTH_ID, objectId: 'mock-project' } },
  { label: '부스 홈페이지', event: { type: 'BOOTH_LAPTOP_INTERACT', boothId: MOCK_BOOTH_ID, objectId: 'mock-laptop' } },
  { label: 'AI 직원', event: { type: 'AI_AGENT_INTERACT', boothId: MOCK_BOOTH_ID, objectId: 'mock-agent', configId: 1 } },
  { label: '설문', event: { type: 'BOOTH_SURVEY_INTERACT', boothId: MOCK_BOOTH_ID, objectId: 'mock-kiosk' } },
  { label: '미니게임', event: { type: 'BOOTH_GAME_INTERACT', boothId: MOCK_BOOTH_ID, objectId: 'mock-portal', configId: 1 } },
  // 부스에 종속되지 않는 월드 상호작용 — payload 에 필드가 없다(S15P21A604-414)
  { label: '내 부스 관리', event: { type: 'WORLD_MANAGEMENT_INTERACT' } },
];

export function MockInteractionBar() {
  // 실제 Unity 는 UnityHost 가 브리지를 잇는다. mock 월드에는 UnityHost 가 없으므로
  // 이 바가 대신 잇는다 — 멱등이라 실제 Unity 와 함께 떠도 문제없다.
  useEffect(() => {
    initUnityBridge();
  }, []);

  function send(event: Record<string, unknown>) {
    window.FestaUnity?.onBoothInteract?.(JSON.stringify(event));
  }

  return (
    <div className="mock-bar" role="group" aria-label="개발용 상호작용 트리거">
      <span className="mock-bar-key">F</span>
      <span className="mock-bar-label">DEV 상호작용</span>
      <span className="mock-bar-sep" />
      {ENTRIES.map((e) => (
        <button key={e.label} type="button" className="mock-bar-btn" onClick={() => send(e.event)}>
          {e.label}
        </button>
      ))}
    </div>
  );
}
