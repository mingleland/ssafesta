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

  // 진행 중인 상담이 있으면 그 부스로 되돌아간다. 없으면 비활성이다 — **여기는 신규 상담
  // Launcher 가 아니다.** 새 상담의 시작 지점은 AI 대화 에스컬레이션 하나이고(spec 011
  // FR-005, S15P21A604-416), 그 자리에서만 대상 부스가 정해진다. HUD 는 이미 시작된 상담으로
  // 돌아가는 문이지 부스를 고르는 자리가 아니라, idle 에서는 어디서 시작하는지만 알려 준다.
  const boothId = state.boothId;
  const canReopen = active && boothId !== null;

  return (
    <div className="cqa">
      <button
        type="button"
        className={'cqa-btn' + (active ? ' cqa-btn-on' : '')}
        aria-label={label}
        title={canReopen ? label : '부스의 AI 직원과 대화하다 사람 상담을 요청할 수 있습니다'}
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
