// Mock Interaction Bar — Unity F 상호작용의 임시 대역 (S15P21A604-406).
// 월드가 목업 정지 화면일 때만 뜬다. 실제 Unity 가 붙으면 이 컴포넌트는 사라지고
// dispatcher 가 BOOTH_*_INTERACT 이벤트로 같은 openOverlay 를 호출한다 — 오버레이 쪽은 그대로다.
// 새 계약을 만들지 않는다: 여기서 여는 payload 는 dispatcher 가 만드는 것과 같은 모양이다.
import { openOverlay } from '../../../shared/types/overlay';
import type { OverlayType } from '../../../shared/types/overlay';
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
    <div className="mock-bar" role="group" aria-label="목업 상호작용">
      <span className="mock-bar-key">F</span>
      <span className="mock-bar-label">상호작용 (목업)</span>
      <span className="mock-bar-sep" />
      {ENTRIES.map((e) => (
        <button key={e.type} type="button" className="mock-bar-btn" onClick={() => openOverlay(e.type, e.payload)}>
          {e.label}
        </button>
      ))}
    </div>
  );
}
