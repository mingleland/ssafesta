// Consultation 방문자 Overlay — Overlay Family 재사용 (S15P21A604-406).
// 데이터는 features/consultation/model/visitor 상태 기계를 그대로 소비한다(-375, 확정 정책 C-01·C-06).
// STOMP destination·payload schema 는 미확정이라 UI 가 만들지 않는다 — channel port 뒤에 있다.
//
// **Close ≠ Cancel** (user-flow-decisions §11.4). 창을 닫아도 상담 요청은 살아 있고, World HUD 의
// 상담 아이콘으로 같은 상태에 다시 들어온다. 상태·카운트다운·채널 구독이 전부 module-level 이라
// 이 컴포넌트가 unmount 돼도 유지된다 — 그래서 cleanup 에서 취소하지 않는다.
// 취소는 [상담 요청 취소] 명시적 액션 하나뿐이다.
import { closeOverlay } from '../../../shared/types/overlay';
import {
  cancelConsultation,
  rerequestConsultation,
  useVisitorConsultation,
} from '../model/visitor';
import { OverlayEmpty, OverlayError, OverlayFrame, OverlayLoading } from '../../overlay/ui/OverlayFrame';
import './consultationOverlay.css';

interface Props {
  /**
   * Overlay Bus 계약상 payload 는 오지만 이 화면은 쓰지 않는다 — 표시 대상은 "지금 진행 중인
   * 상담" 이고 그 boothId 는 상태 기계가 들고 있다(S15P21A604-416). HUD 재진입과 새 요청이
   * 같은 화면을 열기 때문에, 열 때 받은 payload 를 신뢰하면 둘이 어긋난다.
   */
  payload?: { boothId: number };
}

const IcDesk = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M3 12h18M5 12V8a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v4M7 12v6M17 12v6" />
  </svg>
);

function mmss(total: number): string {
  const m = Math.floor(total / 60);
  const s = total % 60;
  return m + ':' + String(s).padStart(2, '0');
}

export function ConsultationOverlay(_props: Props) {
  const state = useVisitorConsultation();
  // 이 화면은 상담을 **시작하지 않는다**. 시작점은 AI 대화 에스컬레이션이고(spec 011 FR-005,
  // S15P21A604-416) 여기는 그 결과 상태를 보여주고 조작하는 자리다. 예전에는 열리자마자
  // requestConsultation 을 불렀는데, 그러면 HUD 로 되돌아오는 것과 새 요청이 구분되지 않는다.
  // cleanup 도 없다: 닫기는 취소가 아니다(§11.4).

  const subtitle =
    state.phase === 'active' ? (state.staffName ?? '직원') + ' 연결됨' : state.phase === 'waiting' ? '대기 중' : '부스 상담';

  return (
    <OverlayFrame
      title="상담"
      subtitle={subtitle}
      size="m"
      icon={IcDesk}
      onClose={closeOverlay}
      status={<span className="ov-note">닫아도 상담 요청은 유지됩니다</span>}
      footer={
        state.phase === 'waiting' ? (
          <>
            <button type="button" className="ov-btn" onClick={closeOverlay}>
              닫기
            </button>
            <button type="button" className="ov-btn" onClick={() => void cancelConsultation()}>
              상담 요청 취소
            </button>
          </>
        ) : state.phase === 'expired' || state.phase === 'error' ? (
          <>
            <button type="button" className="ov-btn" onClick={closeOverlay}>
              닫기
            </button>
            <button type="button" className="ov-btn ov-btn-primary" onClick={() => void rerequestConsultation()}>
              다시 요청
            </button>
          </>
        ) : (
          <button type="button" className="ov-btn" onClick={closeOverlay}>
            닫기
          </button>
        )
      }
    >
      {state.phase === 'idle' && (
        <OverlayEmpty
          title="진행 중인 상담이 없습니다"
          hint="부스의 AI 직원과 대화하다 '사람 상담 요청' 을 누르면 담당자에게 연결됩니다."
        />
      )}

      {state.phase === 'requesting' && <OverlayLoading label="상담을 요청하는 중..." />}

      {state.phase === 'waiting' && (
        <div className="cs-wait">
          <span className="festa-overlay-spinner" />
          <strong>직원 연결을 기다리는 중입니다</strong>
          {state.remainingSeconds !== null && <span className="cs-timer">{mmss(state.remainingSeconds)} 남음</span>}
          <p className="ov-note">직원이 수락하면 바로 대화가 시작됩니다. 창을 닫아도 요청은 유지되며, 월드 우상단 상담 아이콘으로 돌아올 수 있습니다.</p>
        </div>
      )}

      {state.phase === 'active' && (
        <div className="cs-active">
          <div className="cs-staff">
            <span className="cs-avatar">{(state.staffName ?? '직').slice(0, 1)}</span>
            <span>
              <strong>{state.staffName ?? '직원'}</strong>
              <span className="ov-note">상담이 연결되었습니다</span>
            </span>
            <span className="ov-chip cs-live">연결됨</span>
          </div>
          <p className="ov-note">
            대화 채널(WebSocket) 계약은 확정 전이라 이 화면에서는 연결 상태까지만 보여 줍니다.
          </p>
        </div>
      )}

      {state.phase === 'expired' && (
        <OverlayEmpty title="대기 시간이 지났습니다" hint="지금은 응대 가능한 직원이 없습니다. 잠시 후 다시 요청해 주세요." />
      )}
      {state.phase === 'ended' && <OverlayEmpty title="상담이 종료되었습니다" hint="이용해 주셔서 감사합니다." />}
      {state.phase === 'error' && <OverlayError title="상담을 요청하지 못했습니다" message="잠시 후 다시 시도해 주세요." />}
    </OverlayFrame>
  );
}
