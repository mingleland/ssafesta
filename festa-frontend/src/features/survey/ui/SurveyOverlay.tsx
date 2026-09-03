// Survey 응답 Overlay — Overlay Family 재사용 (S15P21A604-406).
// 데이터는 features/survey/model/run 의 상태 기계를 그대로 소비한다(-368).
// BE endpoint·DTO 는 미확정(UNKNOWN)이라 UI 가 만들지 않는다 — mock adapter 가 채운다.
import { useEffect } from 'react';
import { closeOverlay } from '../../../shared/types/overlay';
import type { SurveyQuestionVM } from '../../../shared/contracts/survey';
import {
  canSubmit,
  loadSurveyRun,
  missingRequired,
  setAnswer,
  submitSurveyRun,
  useSurveyRun,
} from '../model/run';
import { OverlayEmpty, OverlayError, OverlayFrame, OverlayLoading } from '../../overlay/ui/OverlayFrame';
import './surveyOverlay.css';

interface Props {
  payload: { boothId: number; surveyId?: string };
}

const IcSurvey = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M8 4h8a2 2 0 0 1 2 2v14l-6-3-6 3V6a2 2 0 0 1 2-2Z" />
    <path d="M9 9h6M9 13h4" />
  </svg>
);

/** 유형별 입력 — 6유형은 spec 010 FR-002 확정분이다(-377 정정). 새 유형을 만들지 않는다. */
function QuestionInput({ q, value }: { q: SurveyQuestionVM; value: unknown }) {
  if (q.type === 'single') {
    const picked = (value as { optionId?: string } | undefined)?.optionId;
    return (
      <div className="sv-options">
        {(q.options ?? []).map((o) => (
          <label key={o.id} className={'sv-option' + (picked === o.id ? ' sv-option-on' : '')}>
            <input type="radio" name={q.id} checked={picked === o.id} onChange={() => setAnswer(q.id, { type: 'single', optionId: o.id })} />
            {o.label}
          </label>
        ))}
      </div>
    );
  }
  if (q.type === 'multi') {
    const picked = (value as { optionIds?: string[] } | undefined)?.optionIds ?? [];
    return (
      <div className="sv-options">
        {(q.options ?? []).map((o) => {
          const on = picked.includes(o.id);
          return (
            <label key={o.id} className={'sv-option' + (on ? ' sv-option-on' : '')}>
              <input
                type="checkbox"
                checked={on}
                onChange={() => setAnswer(q.id, { type: 'multi', optionIds: on ? picked.filter((id) => id !== o.id) : [...picked, o.id] })}
              />
              {o.label}
            </label>
          );
        })}
      </div>
    );
  }
  if (q.type === 'rating') {
    const min = q.scale?.min ?? 1;
    const max = q.scale?.max ?? 5;
    const picked = (value as { value?: number } | undefined)?.value ?? 0;
    const stars = Array.from({ length: max - min + 1 }, (_, i) => min + i);
    return (
      <div className="sv-rating">
        {stars.map((n) => (
          <button
            key={n}
            type="button"
            className={'sv-star' + (n <= picked ? ' sv-star-on' : '')}
            aria-label={n + '점'}
            aria-pressed={n === picked}
            onClick={() => setAnswer(q.id, { type: 'rating', value: n })}
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill={n <= picked ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
              <path d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9L12 3Z" />
            </svg>
          </button>
        ))}
      </div>
    );
  }
  const text = (value as { text?: string } | undefined)?.text ?? '';
  if (q.type === 'short_text') {
    return (
      <input className="sv-input" type="text" value={text} placeholder="답변을 입력하세요" onChange={(e) => setAnswer(q.id, { type: 'short_text', text: e.target.value })} />
    );
  }
  // long_text·application — application 특별 처리(C-03)는 미확정이라 장문 입력으로만 다룬다
  const kind = q.type === 'application' ? 'application' : 'long_text';
  return (
    <textarea
      className="sv-input sv-textarea"
      rows={4}
      value={text}
      placeholder={kind === 'application' ? '지원 내용을 입력하세요' : '답변을 입력하세요'}
      onChange={(e) => setAnswer(q.id, kind === 'application' ? { type: 'application', text: e.target.value } : { type: 'long_text', text: e.target.value })}
    />
  );
}

export function SurveyOverlay({ payload }: Props) {
  const run = useSurveyRun();
  const surveyId = payload.surveyId ?? 'booth-' + payload.boothId;

  useEffect(() => {
    void loadSurveyRun(surveyId);
  }, [surveyId]);

  const missing = run.status === 'ready' ? missingRequired() : [];
  const submitted = run.submit.phase === 'success';

  return (
    <OverlayFrame
      title="설문"
      subtitle={run.status === 'ready' ? run.progress.current + ' / ' + run.progress.total + ' 답변' : '부스 설문'}
      size="l"
      icon={IcSurvey}
      onClose={closeOverlay}
      status={
        run.submit.phase === 'error' ? (
          <span className="ov-alert">제출하지 못했습니다. 다시 시도해 주세요.</span>
        ) : missing.length > 0 ? (
          <span className="ov-note">필수 문항 {missing.length}개가 남았습니다</span>
        ) : (
          // 설문 BE(-130·-190)는 미착수다 — mock adapter 로 도는 상태를 실제인 것처럼 보이게 하지 않는다
          <span className="ov-note">응답 저장은 준비 중입니다 · Esc 로 월드로 돌아갑니다</span>
        )
      }
      footer={
        run.status === 'ready' && !submitted ? (
          <>
            <button type="button" className="ov-btn" onClick={closeOverlay}>
              나중에
            </button>
            <button type="button" className="ov-btn ov-btn-primary" disabled={!canSubmit()} onClick={() => void submitSurveyRun()}>
              {run.submit.phase === 'submitting' ? '제출 중...' : '제출하기'}
            </button>
          </>
        ) : (
          <button type="button" className="ov-btn" onClick={closeOverlay}>
            닫기
          </button>
        )
      }
    >
      {run.status === 'loading' && <OverlayLoading label="설문을 불러오는 중..." />}
      {run.status === 'error' && <OverlayError title="설문을 불러오지 못했습니다" onRetry={() => void loadSurveyRun(surveyId)} />}
      {run.status === 'empty' && <OverlayEmpty title="문항이 없습니다" hint="부스 주인이 문항을 등록하면 참여할 수 있습니다." />}
      {run.status === 'closed' && <OverlayEmpty title="마감된 설문입니다" hint="응답을 더 받지 않습니다." />}
      {submitted && <OverlayEmpty title="응답을 제출했습니다" hint="참여해 주셔서 감사합니다." />}

      {run.status === 'ready' && !submitted && (
        <ol className="sv-list">
          {run.questions.map((q, i) => (
            <li key={q.id} className="sv-item">
              <p className="sv-prompt">
                <span className="sv-index">{i + 1}</span>
                {q.prompt}
                {q.required && <span className="sv-required">필수</span>}
              </p>
              <QuestionInput q={q} value={run.answers[q.id]} />
            </li>
          ))}
        </ol>
      )}
    </OverlayFrame>
  );
}
