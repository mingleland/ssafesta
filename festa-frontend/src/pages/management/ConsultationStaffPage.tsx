// Consultation Staff — 부스 소유자의 상담 운영 화면 (user-flow-decisions §21).
// 진입: Booth Management → CONSULTATION [관리].
//
// World HUD 의 상담 Quick Access 와 역할이 다르다: HUD 는 "지금 벌어지는 상담에 즉시 접근",
// 이 화면은 "대기열을 운영하는 관리 Context". 방문자 상태(visitor.ts)와도 별개 모델이다.
//
// 데이터층은 features/consultation/model/staff.ts 그대로 — 동시 1건 게이트(C-06)와 실패를
// 삼키지 않는 actionError 표현이 그 모델의 계약이다. 실시간 대화 송수신은 범위 밖(P2)이다.
import { useEffect } from 'react';
import {
  acceptRequest,
  canAccept,
  endActiveConsultation,
  loadStaffQueue,
  useStaffConsultation,
} from '../../features/consultation/model/staff';
import { WORLD_RETURN_TO_MANAGEMENT } from '../../features/world/model/gameClientUi';
import { PageShell, ScreenEmpty, ScreenError, ScreenLoading } from '../../features/shell/ui/PageShell';
import './management.css';

function when(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', timeStyle: 'short' }).format(new Date(iso));
}

export function ConsultationStaffPage() {
  const state = useStaffConsultation();

  useEffect(() => {
    void loadStaffQueue();
  }, []);

  const acceptable = canAccept();

  return (
    <PageShell
      title="상담 운영"
      subtitle="방문자가 보낸 상담 요청을 처리합니다"
      backTo={WORLD_RETURN_TO_MANAGEMENT}
      actions={
        <button type="button" className="sc-btn sc-btn-sm" disabled={state.status === 'loading'} onClick={() => void loadStaffQueue()}>
          새로고침
        </button>
      }
    >
      {state.status === 'idle' || state.status === 'loading' ? (
        <ScreenLoading label="대기열을 불러오는 중..." />
      ) : state.status === 'error' ? (
        <ScreenError title="대기열을 불러오지 못했습니다" message="잠시 후 다시 시도해 주세요." onRetry={() => void loadStaffQueue()} />
      ) : (
        <>
          {/* 진행 중인 상담 — C-06 상 동시에 1건뿐이다 */}
          {state.active !== null && (
            <section className="sc-card mg-active">
              <div className="mg-active-head">
                <span className="sc-chip sc-chip-ok">진행 중</span>
                <strong>{state.active.visitorNickname}</strong>
                <button
                  type="button"
                  className="sc-btn"
                  disabled={state.accepting}
                  onClick={() => void endActiveConsultation()}
                >
                  상담 종료
                </button>
              </div>
              {state.active.handoffSummary !== null && (
                <p className="mg-summary">
                  <span className="sc-note">AI 대화 요약</span>
                  {state.active.handoffSummary}
                </p>
              )}
              <p className="sc-note">
                대화 채널(WebSocket) 계약은 확정 전이라 이 화면에서는 연결 상태와 종료까지만 다룹니다.
              </p>
            </section>
          )}

          {state.actionError !== null && (
            <p className="sc-alert" role="alert">
              {state.actionError === 'accept' ? '상담을 수락하지 못했습니다.' : '상담을 종료하지 못했습니다.'} 잠시 후 다시 시도해 주세요.
            </p>
          )}

          <section className="mg-queue">
            <span className="sc-section-title">대기 중인 요청 {state.queue.length}건</span>
            {state.queue.length === 0 ? (
              <ScreenEmpty title="대기 중인 요청이 없습니다" hint="방문자가 상담을 요청하면 여기에 표시됩니다." />
            ) : (
              <ul className="mg-cards">
                {state.queue.map((card) => (
                  <li key={card.requestId} className="sc-card mg-card">
                    <div className="mg-card-head">
                      <strong>{card.visitorNickname}</strong>
                      <span className="sc-note">{when(card.requestedAt)} 요청</span>
                    </div>
                    {card.handoffSummary !== null && <p className="mg-summary">{card.handoffSummary}</p>}
                    <button
                      type="button"
                      className="sc-btn sc-btn-primary sc-btn-sm"
                      disabled={!acceptable}
                      title={state.active !== null ? '이미 진행 중인 상담이 있습니다' : undefined}
                      onClick={() => void acceptRequest(card.requestId)}
                    >
                      {state.accepting ? '처리 중...' : '수락'}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </PageShell>
  );
}
