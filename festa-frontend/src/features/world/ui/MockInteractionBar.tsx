// Mock Interaction Bar — **DEV_ONLY**. Unity F 상호작용의 개발용 대역 (S15P21A604-406).
//
// 제품 HUD 가 아니다(D-08 · hud-decisions: 기능 Launcher 금지). Unity 송신부가 없는 환경에서
// dispatcher 하류(Overlay Bus → OverlayHost → 각 Overlay)를 손으로 검증하기 위한 장치다.
// 노출 조건은 `IS_DEV_INTERACTION_BAR` 하나이며 dev 빌드 + 명시적 플래그를 동시에 요구한다 —
// 프로덕션 번들에서는 조건이 상수 false 라 트리셰이킹으로 사라진다.
// 새 계약을 만들지 않는다: 여기서 여는 payload 는 dispatcher 가 만드는 것과 같은 모양이다.
import { openOverlay } from '../../../shared/types/overlay';
import type { OverlayType } from '../../../shared/types/overlay';
import { openBoothManagement } from '../model/gameClientUi';
import './mockInteractionBar.css';

const MOCK_BOOTH_ID = 1;

interface Entry {
  type: OverlayType;
  label: string;
  payload: unknown;
}

const ENTRIES: Entry[] = [
  { type: 'PROJECT', label: '프로젝트 전시', payload: { boothId: MOCK_BOOTH_ID, objectId: 'mock-project' } },
  { type: 'LAPTOP', label: '부스 홈페이지', payload: { boothId: MOCK_BOOTH_ID, objectId: 'mock-laptop' } },
  { type: 'AI_CHAT', label: 'AI 직원', payload: { boothId: MOCK_BOOTH_ID, objectId: 'mock-agent', agentId: 1 } },
  { type: 'SURVEY', label: '설문', payload: { boothId: MOCK_BOOTH_ID, objectId: 'mock-survey' } },
  { type: 'CONSULTATION', label: '상담', payload: { boothId: MOCK_BOOTH_ID, objectId: 'mock-desk' } },
  { type: 'GAME', label: '미니게임', payload: { boothId: MOCK_BOOTH_ID, objectId: 'mock-portal', configId: 1 } },
];

export function MockInteractionBar() {
  return (
    <div className="mock-bar" role="group" aria-label="개발용 상호작용 트리거">
      <span className="mock-bar-key">F</span>
      <span className="mock-bar-label">DEV 상호작용</span>
      <span className="mock-bar-sep" />
      {ENTRIES.map((e) => (
        <button key={e.type} type="button" className="mock-bar-btn" onClick={() => openOverlay(e.type, e.payload)}>
          {e.label}
        </button>
      ))}
      {/* Booth Management NPC 대역 — Unity 이벤트 계약(G-1) 이 오면 dispatcher 가 같은 함수를 부른다 */}
      <button type="button" className="mock-bar-btn" onClick={openBoothManagement}>
        내 부스 관리
      </button>
    </div>
  );
}
