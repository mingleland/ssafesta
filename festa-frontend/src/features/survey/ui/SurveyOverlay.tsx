// Survey 응답 Overlay — Overlay Family 재사용 (S15P21A604-406).
// 데이터는 features/survey/model/run 의 상태 기계를 그대로 소비한다(-368).
//
// **한 화면이 두 진입을 받는다** (S15P21A604-608). 부스 설문은 Unity 가 boothId 를 주고 서버가 그
// 부스의 설문을 돌려주며(계약 §5, -528), 이벤트 설문은 부스에 속하지 않아 surveyKey 로 찾는다.
// 그 둘 말고 다른 것은 같다 — 문항 유형·검증·제출·마감·오류가 전부 하나라서, 오버레이를 둘로
// 나누면 문항 유형이 늘 때마다 두 곳을 고쳐야 하고 한쪽이 낡는다. 새 OverlayType 도 만들지 않는다.
import { useEffect } from 'react';
import { useSession } from '../../auth/model/session';
import { closeOverlay } from '../../../shared/types/overlay';
import type { SurveyQuestionVM, SurveySource } from '../../../shared/contracts/survey';
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
  // surveyId 는 받지 않는다 — 어느 경로든 서버가 설문을 찾아 주고 그 id 를 run 응답에 싣는다
  payload: SurveySource;
}

const IcSurvey = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M8 4h8a2 2 0 0 1 2 2v14l-6-3-6 3V6a2 2 0 0 1 2-2Z" />
    <path d="M9 9h6M9 13h4" />
  </svg>
);

/** 참여 시각 표시 — 저장소에 공유 날짜 util 이 없어 다른 화면과 같은 관례(인라인 Intl)를 따른다 */
function respondedAt(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'short', timeStyle: 'short' }).format(
    new Date(iso),
  );
}

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
  const session = useSession();
  // payload 객체는 매 렌더 새 참조라 그대로 의존성에 넣으면 effect 가 계속 돈다. 원시값 둘로 풀고
  // effect 안에서 다시 좁힌다 — 캐스팅 없이 타입이 맞는다
  const boothId = payload.kind === 'booth' ? payload.boothId : null;
  const surveyKey = payload.kind === 'event' ? payload.surveyKey : null;

  const reload = (): void => {
    if (boothId !== null) void loadSurveyRun({ kind: 'booth', boothId });
    else if (surveyKey !== null) void loadSurveyRun({ kind: 'event', surveyKey });
  };

  // reload 는 매 렌더 새 함수지만 의존성은 원시값 둘이다 — 그래서 다시 돌지 않는다
  useEffect(reload, [boothId, surveyKey]);

  const missing = run.status === 'ready' ? missingRequired() : [];
  // 방금 제출했든 지난번에 참여했든 화면은 같다 — 참여가 끝났다는 사실이 같고, 상태를 늘리면
  // 렌더 분기만 늘고 전이는 같아진다
  const submitted = run.submit.phase === 'success' || run.respondedAt !== null;
  // 게스트가 제출할 수 없는 설문은 두 종류다 — 코인이 걸린 부스 설문(403 MEMBER_ONLY)과 보상이
  // 없어도 회원 전용인 이벤트 설문. run 응답이 두 값을 싣는 이유가 이것이라 **제출 전에** 알린다
  const guestBlocked = session.kind === 'guest' && (run.rewardCoin > 0 || run.memberOnly);

  return (
    <OverlayFrame
      title="설문"
      subtitle={
        run.status === 'ready' && !submitted
          ? run.progress.current + ' / ' + run.progress.total + ' 답변'
          : boothId !== null
            ? '부스 설문'
            : '이벤트 설문'
      }
      size="l"
      icon={IcSurvey}
      onClose={closeOverlay}
      status={
        run.submit.phase === 'error' ? (
          // 서버 문장을 그대로 쓴다 (docs/08 §1.3-1). 없을 때만 일반 문구인데, 그 문구도
          // "다시 시도" 를 권하지 않는다 — 재시도로 풀리지 않는 오류가 여기 섞여 온다
          <span className="ov-alert">{run.submit.errorMessage ?? '제출하지 못했습니다.'}</span>
        ) : guestBlocked ? (
          <span className="ov-alert">
            {run.rewardCoin > 0 ? '코인이 걸린 설문이라 로그인해야 참여할 수 있습니다' : '회원만 참여할 수 있는 설문입니다 — 로그인해 주세요'}
          </span>
        ) : missing.length > 0 ? (
          <span className="ov-note">필수 문항 {missing.length}개가 남았습니다</span>
        ) : // 보상 안내는 **아직 참여할 수 있을 때만** 성립한다. status·submit 은 독립 축이라
        // 이 조건이 없으면 마감된 설문과 제출 완료 화면에서도 "참여하면 N 코인" 이 흘러나온다
        run.status === 'ready' && !submitted && run.rewardCoin > 0 ? (
          <span className="ov-note">참여하면 {run.rewardCoin} 코인을 받습니다 · Esc 로 월드로 돌아갑니다</span>
        ) : (
          <span className="ov-note">Esc 로 월드로 돌아갑니다</span>
        )
      }
      footer={
        run.status === 'ready' && !submitted ? (
          <>
            <button type="button" className="ov-btn" onClick={closeOverlay}>
              나중에
            </button>
            <button type="button" className="ov-btn ov-btn-primary" disabled={guestBlocked || !canSubmit()} onClick={() => void submitSurveyRun()}>
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
      {run.status === 'error' && <OverlayError title="설문을 불러오지 못했습니다" onRetry={reload} />}
      {run.status === 'empty' && (
        <OverlayEmpty
          title="문항이 없습니다"
          hint={boothId !== null ? '부스 주인이 문항을 등록하면 참여할 수 있습니다.' : '문항이 등록되면 참여할 수 있습니다.'}
        />
      )}
      {run.status === 'closed' && <OverlayEmpty title="마감된 설문입니다" hint="응답을 더 받지 않습니다." />}
      {submitted && (
        <OverlayEmpty
          title={run.submit.phase === 'success' ? '응답을 제출했습니다' : '이미 참여한 설문입니다'}
          hint={
            run.submit.rewardedCoin !== null && run.submit.rewardedCoin > 0
              ? `${run.submit.rewardedCoin} 코인을 받았습니다. 참여해 주셔서 감사합니다.`
              : run.respondedAt !== null && run.submit.phase !== 'success'
                ? `${respondedAt(run.respondedAt)}에 참여하셨습니다. 감사합니다.`
                : '참여해 주셔서 감사합니다.'
          }
        />
      )}

      {/*
        마감된 설문도 문항을 남긴다 — 계약 §5: "closed 면 문항은 그대로 싣는다. 화면이
        '마감된 설문입니다' 를 보여주되 무엇을 물었는지는 남는다." 입력은 붙이지 않는다.
        제출은 §6 이 409 로 막고, 여기서 답을 받을 수 있는 것처럼 보일 이유가 없다.
      */}
      {(run.status === 'ready' || run.status === 'closed') && !submitted && (
        <ol className="sv-list">
          {run.questions.map((q, i) => (
            <li key={q.id} className="sv-item">
              <p className="sv-prompt selectable">
                <span className="sv-index">{i + 1}</span>
                {q.prompt}
                {q.required && <span className="sv-required">필수</span>}
              </p>
              {run.status === 'ready' && <QuestionInput q={q} value={run.answers[q.id]} />}
            </li>
          ))}
        </ol>
      )}
    </OverlayFrame>
  );
}
