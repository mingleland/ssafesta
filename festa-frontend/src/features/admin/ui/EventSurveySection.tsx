// 이벤트 설문 — 운영·모니터링·결과 확인이 중심이다. 부스 설문 작성 UI 를 재사용하지 않는다.
// entrants(BE)·run(BE) 는 real 로 잇고, 집계·개별 응답·목록은 [FE contract] 다.
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import type { EventQuestionAggregate } from '../../../entities/admin/types';
import { Empty, ErrorBanner, KeyValue, Loading, Pager, fmtTime } from './common';

export function EventSurveySection({ surveyKey, responseId, onSelectSurvey, onSelectResponse }: {
  surveyKey: string | null; responseId: number | null; onSelectSurvey: (key: string | null) => void; onSelectResponse: (id: number | null) => void;
}) {
  const list = useQuery({ queryKey: ['admin', 'event-surveys'], queryFn: () => adminApi.listEventSurveys() });
  if (list.isPending) return <section className="sc-card"><Loading label="공식 설문을 불러오는 중..." /></section>;
  if (list.isError) return <section className="sc-card"><ErrorBanner error={list.error} onRetry={() => void list.refetch()} /></section>;
  if (list.data.length === 0) return <section className="sc-card"><Empty title="공식 이벤트 설문이 없습니다" /></section>;

  const key = surveyKey ?? list.data[0].surveyKey;
  return (
    <div className="ad-work">
      {list.data.length > 1 && (
        <div className="ad-toolbar">
          <select aria-label="설문 선택" value={key} onChange={(e) => { onSelectSurvey(e.target.value); onSelectResponse(null); }}>
            {list.data.map((s) => <option key={s.surveyKey} value={s.surveyKey}>{s.title}</option>)}
          </select>
        </div>
      )}
      <SurveyDetail surveyKey={key} responseId={responseId} onSelectResponse={onSelectResponse} />
    </div>
  );
}

function SurveyDetail({ surveyKey, responseId, onSelectResponse }: { surveyKey: string; responseId: number | null; onSelectResponse: (id: number | null) => void }) {
  const summary = useQuery({ queryKey: ['admin', 'event-survey', surveyKey], queryFn: () => adminApi.getEventSurvey(surveyKey) });
  const aggregate = useQuery({ queryKey: ['admin', 'event-aggregate', surveyKey], queryFn: () => adminApi.getEventAggregate(surveyKey) });
  const [page, setPage] = useState(0);
  const entrants = useQuery({ queryKey: ['admin', 'entrants', surveyKey, page], queryFn: () => adminApi.listEntrants(surveyKey, page, 10) });

  return (
    <>
      <section className="sc-card ad-work" aria-label="응답 현황">
        {summary.isPending && <Loading />}
        {summary.isError && <ErrorBanner error={summary.error} onRetry={() => void summary.refetch()} />}
        {summary.isSuccess && (
          <>
            <div className="ad-head">
              <h2>{summary.data.title}</h2>
              <span className={`ad-chip ${summary.data.closed ? 'ad-chip-bad' : 'ad-chip-ok'}`}>{summary.data.closed ? '마감' : '진행 중'}</span>
            </div>
            <div className="ad-stats">
              <div className="ad-stat"><strong>{summary.data.entrantCount}</strong><span>총 참여자</span></div>
              <div className="ad-stat"><strong>{summary.data.questionCount}</strong><span>문항</span></div>
              <div className="ad-stat"><strong>{summary.data.rewardCoin}</strong><span>참여 보상 코인</span></div>
              <div className="ad-stat"><strong className="ad-muted" style={{ fontSize: 13 }}>{summary.data.surveyKey}</strong><span>설문 key · #{summary.data.surveyId}</span></div>
            </div>
          </>
        )}
      </section>

      <div className="ad-split">
        <section className="sc-card ad-work" aria-label="응답 결과">
          <h3 className="sc-section-title">질문별 집계 <span className="ad-badge-fe">FE 계약</span></h3>
          {aggregate.isPending && <Loading />}
          {aggregate.isError && <ErrorBanner error={aggregate.error} onRetry={() => void aggregate.refetch()} />}
          {aggregate.isSuccess && aggregate.data.length === 0 && <Empty title="집계할 응답이 없습니다" />}
          {aggregate.isSuccess && aggregate.data.map((q) => <QuestionAggregate key={q.questionId} q={q} />)}
        </section>

        <section className="sc-card ad-work" aria-label="참여자">
          <h3 className="sc-section-title">참여자</h3>
          {entrants.isPending && <Loading />}
          {entrants.isError && <ErrorBanner error={entrants.error} onRetry={() => void entrants.refetch()} />}
          {entrants.isSuccess && entrants.data.content.length === 0 && <Empty title="아직 참여자가 없습니다" />}
          {entrants.isSuccess && entrants.data.content.length > 0 && (
            <>
              <table className="ad-table">
                <thead><tr><th>제출</th><th>닉네임</th><th>회원</th><th /></tr></thead>
                <tbody>
                  {entrants.data.content.map((e) => (
                    <tr key={e.responseId} data-selectable="" aria-selected={e.responseId === responseId} onClick={() => onSelectResponse(e.responseId)}>
                      <td>{fmtTime(e.submittedAt)}</td>
                      <td>{e.nickname}</td>
                      <td className="num">#{e.userId}</td>
                      <td><span className="ad-muted">응답 보기 →</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager page={entrants.data.page} totalPages={entrants.data.totalPages} onChange={setPage} />
            </>
          )}
          {responseId !== null && <ResponseDetail surveyKey={surveyKey} responseId={responseId} onClose={() => onSelectResponse(null)} />}
        </section>
      </div>
    </>
  );
}

function QuestionAggregate({ q }: { q: EventQuestionAggregate }) {
  const max = Math.max(1, ...q.options.map((o) => o.count));
  return (
    <div className="ad-form" style={{ gap: 6 }}>
      <strong>{q.prompt} <span className="ad-muted">· {q.answered}명 응답</span></strong>
      {q.options.map((o) => (
        <div key={o.label} className="ad-bar">
          <span style={{ minWidth: 96 }}>{o.label}</span>
          <span className="ad-bar-track"><i style={{ width: `${(o.count / max) * 100}%` }} /></span>
          <span className="num" style={{ minWidth: 28, textAlign: 'right' }}>{o.count}</span>
        </div>
      ))}
      {q.textSamples.length > 0 && (
        <ul className="sc-note" style={{ paddingLeft: 18, margin: 0 }}>
          {q.textSamples.map((t, i) => <li key={i}>{t}</li>)}
        </ul>
      )}
    </div>
  );
}

function ResponseDetail({ surveyKey, responseId, onClose }: { surveyKey: string; responseId: number; onClose: () => void }) {
  const detail = useQuery({ queryKey: ['admin', 'event-response', surveyKey, responseId], queryFn: () => adminApi.getEventResponse(surveyKey, responseId) });
  return (
    <div className="sc-card ad-work" aria-label="개별 응답">
      <div className="ad-head">
        <h3 className="sc-section-title" style={{ margin: 0 }}>개별 응답 #{responseId} <span className="ad-badge-fe">FE 계약</span></h3>
        <button type="button" className="sc-btn sc-btn-sm" onClick={onClose}>닫기</button>
      </div>
      {detail.isPending && <Loading />}
      {detail.isError && <ErrorBanner error={detail.error} onRetry={() => void detail.refetch()} />}
      {detail.isSuccess && (
        <>
          <KeyValue rows={[['참여자', `${detail.data.nickname} (#${detail.data.userId})`], ['제출', fmtTime(detail.data.submittedAt)]]} />
          <dl className="ad-kv">
            {detail.data.answers.map((a) => (
              <div key={a.questionId}><dt>{a.prompt}</dt><dd>{a.value === '' ? <span className="ad-muted">(무응답)</span> : a.value}</dd></div>
            ))}
          </dl>
        </>
      )}
    </div>
  );
}

