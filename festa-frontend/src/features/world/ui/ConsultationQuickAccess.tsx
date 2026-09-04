// Consultation Quick Access — World HUD 우상단 (hud-decisions 허용 4번 · user-flow-decisions §11).
//
// 상담만 HUD 자리를 얻는 이유: 요청→대기→수락→진행→만료→종료의 **시간 지속성**을 갖는 유일한
// 방문자 기능이라, 오버레이를 닫고 월드로 돌아간 뒤에도 상태에 즉시 닿을 경로가 필요하다.
// 다른 기능이 "자주 쓴다"는 이유로 여기 붙지 않는다.
//
// 표기는 상태 유무를 점(●)으로만 나타낸다 — 실제 데이터에 없는 unread count·숫자를 만들지 않는다.
import { openOverlay } from '../../../shared/types/overlay';
import { useVisitorConsultation } from '../../consultation/model/visitor';
import './consultationQuickAccess.css';

const IcBubble = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M20 15a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z" />
  </svg>
);

/** phase → 사용자 언어. 진행 중인 상담이 있으면 점을 켠다 */
function describe(phase: string): { label: string; active: boolean } {
  switch (phase) {
    case 'requesting':
      return { label: '상담 요청 중', active: true };
    case 'waiting':
      return { label: '상담 대기 중', active: true };
    case 'active':
      return { label: '상담 진행 중', active: true };
    case 'expired':
      return { label: '상담 대기 시간 만료', active: true };
    default:
      return { label: '상담', active: false };
  }
}

export function ConsultationQuickAccess() {
  const state = useVisitorConsultation();
  const { label, active } = describe(state.phase);

  // 진행 중인 상담이 있으면 그 부스로 되돌아간다. 없으면 대상 부스를 FE 가 정할 수 없다 —
  // 새 상담의 대상 Booth 결정은 Unity/BE 계약 대기(G-3)라 여기서 부스 목록을 발명하지 않는다.
  const boothId = state.boothId;
  const canReopen = active && boothId !== null;

  return (
    <div className="cqa">
      <button
        type="button"
        className={'cqa-btn' + (active ? ' cqa-btn-on' : '')}
        aria-label={label}
        title={canReopen ? label : '상담은 부스의 상담 데스크에서 시작합니다'}
        disabled={!canReopen}
        onClick={() => {
          if (boothId !== null) openOverlay('CONSULTATION', { boothId });
        }}
      >
        {IcBubble}
        {active && <span className="cqa-dot" aria-hidden="true" />}
      </button>
      {active && <span className="cqa-label">{label}</span>}
    </div>
  );
}
