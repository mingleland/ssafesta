// Survey Management — 부스 소유자의 설문 편집·결과 (user-flow-decisions §20).
// 진입: Booth Management → SURVEY [관리]. 한 Context 안에서 [설문 편집] / [결과] 를 전환한다 —
// Booth Management 최상위에 카드 두 개로 쪼개지 않는다.
//
// 데이터층은 features/survey/model 의 builder.ts·result.ts 를 그대로 소비한다. 질문 6유형은
// spec 010 FR-002 확정분이며 새 유형을 만들지 않고, 결과도 모델에 있는 집계만 보여준다 —
// 응답률·이탈률 같은 지표를 발명하지 않는다.
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  addQuestion,
  loadSurveyBuilder,
  removeQuestion,
  reorderQuestion,
  saveSurveyBuilder,
  updateQuestion,
  updateTitle,
  useSurveyBuilder,
  validateBuilder,
} from '../../features/survey/model/builder';
import { loadNextTextPage, loadSurveyResult, useSurveyResult } from '../../features/survey/model/result';
import type { SurveyQuestionType } from '../../shared/contracts/survey';
import { WORLD_RETURN_TO_MANAGEMENT } from '../../features/world/model/gameClientUi';
import { PageShell, ScreenEmpty, ScreenError, ScreenLoading } from '../../features/shell/ui/PageShell';
import './management.css';

const TYPE_LABEL: Record<SurveyQuestionType, string> = {
  single: '객관식',
  multi: '복수선택',
  rating: '별점',
  short_text: '단답',
  long_text: '장문',
  application: '지원서',
};

const ADDABLE: SurveyQuestionType[] = ['single', 'multi', 'rating', 'short_text', 'long_text', 'application'];

// 응답 시각 표시 — 상태는 wire ISO 원형을 들고 있고 형식으로 바꾸는 것은 여기 한 곳이다.
// 저장소에 공유 날짜 util 이 없어 다른 화면들과 같은 관례(인라인 Intl.DateTimeFormat)를 따른다.
function respondedAt(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'short', timeStyle: 'short' }).format(
    new Date(iso),
  );
}

function BuilderTab({ boothId }: { boothId: number }) {
  const state = useSurveyBuilder();

  useEffect(() => {
    void loadSurveyBuilder(boothId);
  }, [boothId]);

  if (state.status === 'idle' || state.status === 'loading') return <ScreenLoading label="설문을 불러오는 중..." />;
  if (state.status === 'error') {
    return <ScreenError title="설문을 불러오지 못했습니다" message="잠시 후 다시 시도해 주세요." onRetry={() => void loadSurveyBuilder(boothId)} />;
  }

  const issues = validateBuilder();
  const saving = state.save.phase === 'submitting';
  const questions = state.draft.questions;

  return (
    <div className="mg-builder">
      <section className="sc-card mg-form">
        <label className="mg-field">
          <span className="mg-label">설문 제목</span>
          <input
            className="mg-input"
            value={state.draft.title}
            disabled={saving}
            onChange={(e) => updateTitle(e.target.value)}
          />
        </label>
      </section>

      {questions.length === 0 ? (
        <ScreenEmpty title="아직 문항이 없습니다" hint="아래에서 유형을 골라 문항을 추가하세요." />
      ) : (
        <ol className="mg-qlist">
          {questions.map((q, i) => (
            <li key={q.id} className="sc-card mg-q">
              <div className="mg-q-head">
                <span className="sc-chip">{TYPE_LABEL[q.type]}</span>
                <span className="mg-q-index">{i + 1}번</span>
                <div className="mg-q-actions">
                  <button type="button" className="sc-btn sc-btn-sm" disabled={i === 0 || saving} onClick={() => reorderQuestion(i, i - 1)}>
                    위로
                  </button>
                  <button
                    type="button"
                    className="sc-btn sc-btn-sm"
                    disabled={i === questions.length - 1 || saving}
                    onClick={() => reorderQuestion(i, i + 1)}
                  >
                    아래로
                  </button>
                  <button type="button" className="sc-btn sc-btn-sm" disabled={saving} onClick={() => removeQuestion(q.id)}>
                    삭제
                  </button>
                </div>
              </div>

              <input
                className="mg-input"
                value={q.prompt}
                placeholder="질문을 입력하세요"
                disabled={saving}
                onChange={(e) => updateQuestion(q.id, { prompt: e.target.value })}
              />

              <label className="mg-check">
                <input
                  type="checkbox"
                  checked={q.required}
                  disabled={saving}
                  onChange={(e) => updateQuestion(q.id, { required: e.target.checked })}
                />
                필수 응답
              </label>

              {(q.type === 'single' || q.type === 'multi') && (
                <div className="mg-options">
                  {(q.options ?? []).map((o, oi) => (
                    <input
                      key={o.id}
                      className="mg-input mg-input-sm"
                      value={o.label}
                      placeholder={`선택지 ${oi + 1}`}
                      disabled={saving}
                      onChange={(e) =>
                        updateQuestion(q.id, {
                          options: (q.options ?? []).map((x) => (x.id === o.id ? { ...x, label: e.target.value } : x)),
                        })
                      }
                    />
                  ))}
                  <button
                    type="button"
                    className="sc-btn sc-btn-sm"
                    disabled={saving}
                    onClick={() =>
                      updateQuestion(q.id, {
                        options: [...(q.options ?? []), { id: `o-${(q.options?.length ?? 0) + 1}`, label: '' }],
                      })
                    }
                  >
                    선택지 추가
                  </button>
                </div>
              )}

              {q.type === 'rating' && (
                <span className="sc-note">
                  {q.scale?.min ?? 1} ~ {q.scale?.max ?? 5}점 척도
                </span>
              )}
            </li>
          ))}
        </ol>
      )}

      <section className="sc-card mg-add">
        <span className="sc-section-title">문항 추가</span>
        <div className="mg-add-types">
          {ADDABLE.map((t) => (
            <button key={t} type="button" className="sc-btn sc-btn-sm" disabled={saving} onClick={() => addQuestion(t)}>
              {TYPE_LABEL[t]}
            </button>
          ))}
        </div>
      </section>

      <div className="mg-form-foot">
        {issues.length > 0 && (
          <ul className="mg-issues" role="alert">
            {issues.map((issue, i) => (
              <li key={i}>{issue.message}</li>
            ))}
          </ul>
        )}
        {state.save.phase === 'success' && <span className="mg-ok">저장했습니다</span>}
        {state.save.phase === 'error' && <span className="sc-alert">저장하지 못했습니다.</span>}
        <button
          type="button"
          className="sc-btn sc-btn-primary"
          disabled={saving || issues.length > 0 || !state.dirty}
          onClick={() => void saveSurveyBuilder()}
        >
          {saving ? '저장 중...' : '설문 저장'}
        </button>
      </div>
    </div>
  );
}

function ResultTab({ boothId }: { boothId: number }) {
  const state = useSurveyResult();

  useEffect(() => {
    void loadSurveyResult(boothId);
  }, [boothId]);

  if (state.status === 'idle' || state.status === 'loading') return <ScreenLoading label="결과를 불러오는 중..." />;
  if (state.status === 'error') {
    return <ScreenError title="결과를 불러오지 못했습니다" message="잠시 후 다시 시도해 주세요." onRetry={() => void loadSurveyResult(boothId)} />;
  }
  if (state.status === 'empty') return <ScreenEmpty title="아직 응답이 없습니다" hint="방문자가 설문에 답하면 여기에 집계가 쌓입니다." />;

  return (
    <div className="mg-result">
      <p className="sc-note">
        전체 응답 {state.totalResponses}건
        {/* spec 010 US2 시나리오 1 — 응답 수와 함께 최초·최근 응답 시각을 보인다 */}
        {state.firstRespondedAt !== null && state.lastRespondedAt !== null && (
          <>
            {' · '}최초 {respondedAt(state.firstRespondedAt)}
            {' · '}최근 {respondedAt(state.lastRespondedAt)}
          </>
        )}
      </p>
      {state.perQuestion.map((agg) => (
        <section key={agg.questionId} className="sc-card mg-agg">
          {agg.kind === 'choice' ? (
            <>
              <span className="sc-section-title">
                선택 분포 <span className="sc-note">응답 {agg.answeredCount}건</span>
              </span>
              <ul className="mg-bars">
                {agg.counts.map((c) => {
                  // 분모는 **그 문항에 답한 응답 수**다(계약 §7). 선택 수 합으로 나누면 복수선택에서
                  // 한 사람이 두 번 세어져 "응답자 중 몇 %" 가 아니라 "선택 중 몇 %" 가 된다.
                  // 복수선택은 합이 100% 를 넘고 그 사실이 드러나는 편이 옳다
                  const total = agg.answeredCount || 1;
                  return (
                    <li key={c.optionId}>
                      <span className="mg-bar-label">{c.label}</span>
                      <span className="mg-bar">
                        <span className="mg-bar-fill" style={{ width: `${(c.count / total) * 100}%` }} />
                      </span>
                      <span className="mg-bar-count">{c.count}</span>
                    </li>
                  );
                })}
              </ul>
            </>
          ) : (
            <>
              <span className="sc-section-title">별점</span>
              <p className="mg-avg">
                {/* 응답 0건이면 서버가 average 를 null 로 준다 — 0 으로 나누지 않는다(계약 §7) */}
                {agg.average === null ? '—' : agg.average.toFixed(1)}{' '}
                <span className="sc-note">평균 · 응답 {agg.answeredCount}건</span>
              </p>
              <ul className="mg-bars">
                {agg.distribution.map((d) => {
                  const total = agg.answeredCount || 1;
                  return (
                    <li key={d.value}>
                      <span className="mg-bar-label">{d.value}점</span>
                      <span className="mg-bar">
                        <span className="mg-bar-fill" style={{ width: `${(d.count / total) * 100}%` }} />
                      </span>
                      <span className="mg-bar-count">{d.count}</span>
                    </li>
                  );
                })}
              </ul>
            </>
          )}
        </section>
      ))}

      {state.textAnswers.items.length > 0 && (
        <section className="sc-card">
          <span className="sc-section-title">주관식 답변</span>
          <ul className="mg-texts">
            {state.textAnswers.items.map((a, i) => (
              // 같은 문항에 같은 답이 있을 수 있어 index 를 함께 쓴다 — responseId 는 Port 에 없다
              <li key={`${a.questionId}-${i}`}>
                <span className="sc-note">문항 {a.questionId}</span> {a.text}
              </li>
            ))}
          </ul>
          {state.textAnswers.hasNext && (
            <button
              type="button"
              className="sc-btn sc-btn-sm"
              disabled={state.textAnswers.loadingNext}
              onClick={() => void loadNextTextPage()}
            >
              {state.textAnswers.loadingNext ? '불러오는 중...' : '더 보기'}
            </button>
          )}
        </section>
      )}
    </div>
  );
}

export function SurveyManagementPage() {
  const { boothId: boothIdParam } = useParams<{ boothId: string }>();
  const [tab, setTab] = useState<'builder' | 'result'>('builder');
  const boothId = Number(boothIdParam);

  // 라우트가 :boothId 없이 매칭될 수 없지만, 숫자가 아닌 값이 오면 조회 경로가 조용히 깨진다 —
  // 합성 id 를 만들어 덮던 자리(`booth-${boothId ?? '1'}`)를 없앤 대신 여기서 드러낸다
  if (!Number.isSafeInteger(boothId) || boothId < 1) {
    return <ScreenError title="올바르지 않은 부스입니다" message="주소를 확인해 주세요." />;
  }

  return (
    <PageShell
      title="설문 관리"
      subtitle="방문자에게 보여줄 설문을 만들고 응답을 확인합니다"
      backTo={WORLD_RETURN_TO_MANAGEMENT}
      actions={
        <div className="mg-tabs" role="tablist">
          <button type="button" role="tab" aria-selected={tab === 'builder'} className={'mg-tab' + (tab === 'builder' ? ' mg-tab-on' : '')} onClick={() => setTab('builder')}>
            설문 편집
          </button>
          <button type="button" role="tab" aria-selected={tab === 'result'} className={'mg-tab' + (tab === 'result' ? ' mg-tab-on' : '')} onClick={() => setTab('result')}>
            결과
          </button>
        </div>
      }
    >
      {tab === 'builder' ? <BuilderTab boothId={boothId} /> : <ResultTab boothId={boothId} />}
    </PageShell>
  );
}
