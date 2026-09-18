// Survey Management — 부스 소유자의 설문 편집·결과 (user-flow-decisions §20).
// 진입: Booth Management → SURVEY [관리]. 한 Context 안에서 [설문 편집] / [결과] 를 전환한다 —
// Booth Management 최상위에 카드 두 개로 쪼개지 않는다.
//
// 데이터층은 features/survey/model 의 builder.ts·result.ts 를 그대로 소비한다. 질문 6유형은
// spec 010 FR-002 확정분이며 새 유형을 만들지 않고, 결과도 모델에 있는 집계만 보여준다 —
// 응답률·이탈률 같은 지표를 발명하지 않는다.
import { useEffect, useState } from 'react';
import {
  addQuestion,
  loadSurveyBuilder,
  removeQuestion,
  reorderQuestion,
  saveSurveyBuilder,
  updateQuestion,
  updateRewardCoin,
  updateTitle,
  useSurveyBuilder,
  validateBuilder,
} from '../../features/survey/model/builder';
import { loadNextTextPage, loadSurveyResult, useSurveyResult } from '../../features/survey/model/result';
import type { SurveyQuestionType } from '../../shared/contracts/survey';
import { ScreenEmpty, ScreenError, ScreenLoading } from '../../features/shell/ui/PageShell';
import { ManagementScreen, useManagementBoothId } from '../../features/booth/ui/ManagementScreen';
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

/** 문항 추가 — 6유형 버튼을 늘 펼쳐 두지 않고 누른 뒤 고르게 한다 (유형은 spec 010 FR-002 확정분) */
function AddQuestion({ disabled, onAdd }: { disabled: boolean; onAdd: (type: SurveyQuestionType) => void }) {
  const [open, setOpen] = useState(false);
  if (!open) {
    return (
      <button type="button" className="sc-btn sc-btn-sm" disabled={disabled} onClick={() => setOpen(true)}>
        + 문항 추가
      </button>
    );
  }
  return (
    <div className="mg-add-types" role="group" aria-label="문항 유형 선택">
      {ADDABLE.map((t) => (
        <button
          key={t}
          type="button"
          className="sc-btn sc-btn-sm"
          disabled={disabled}
          onClick={() => {
            onAdd(t);
            setOpen(false);
          }}
        >
          {TYPE_LABEL[t]}
        </button>
      ))}
      <button type="button" className="sc-btn sc-btn-sm" onClick={() => setOpen(false)}>
        취소
      </button>
    </div>
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
        <label className="mg-field">
          <span className="mg-label">응답 보상 코인 (0 = 보상 없음)</span>
          <input
            className="mg-input"
            type="number"
            min={0}
            step={1}
            inputMode="numeric"
            value={state.draft.rewardCoin}
            disabled={saving}
            aria-invalid={state.save.fieldError?.field === 'rewardCoin' || undefined}
            onChange={(e) => updateRewardCoin(e.target.value === '' ? 0 : Number(e.target.value))}
          />
          {/* C-05: 보상이 걸린 부스 설문은 게스트 403 MEMBER_ONLY — 값을 넣는 화면에서 그 사실을 말한다 */}
          {state.draft.rewardCoin > 0 && (
            <span className="mg-hint">보상이 있는 설문은 회원만 참여할 수 있습니다 — 게스트는 응답할 수 없습니다.</span>
          )}
          {state.save.fieldError?.field === 'rewardCoin' && (
            <span className="sc-alert" role="alert">{state.save.fieldError.message}</span>
          )}
        </label>
      </section>

      {questions.length === 0 ? (
        <div className="mg-empty-action">
          <ScreenEmpty title="아직 문항이 없습니다" hint="첫 문항을 추가해 설문을 시작하세요." />
          <AddQuestion disabled={saving} onAdd={addQuestion} />
        </div>
      ) : (
        <>
        <div className="mg-list-head">
          <span className="sc-section-title">문항 {questions.length}개</span>
          <AddQuestion disabled={saving} onAdd={addQuestion} />
        </div>
        <ol className="mg-qlist">
          {questions.map((q, i) => (
            <li key={q.id} className="sc-card mg-q">
              <div className="mg-q-head">
                <span className="mg-q-no">{String(i + 1).padStart(2, '0')}</span>
                <span className="sc-chip">{TYPE_LABEL[q.type]}</span>
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
        </>
      )}
    </div>
  );
}

/**
 * 편집 탭의 저장 동작 — 화면 공통 footer 에 선다.
 *
 * 본문이 아니라 여기 두는 이유는 §4 공통 골격이다: 주요 액션은 화면마다 같은 자리에 있어야 한다.
 * builder 는 module store(useSyncExternalStore)라 부모도 그대로 구독할 수 있어, 자식이 부모에게
 * 노드를 올려보내는 배선을 만들지 않는다.
 */
function BuilderSaveAction() {
  const state = useSurveyBuilder();
  if (state.status !== 'ready') return null;

  const issues = validateBuilder();
  const saving = state.save.phase === 'submitting';
  return (
    <div className="mg-foot">
      {issues.length > 0 && (
        <span className="sc-alert" role="alert">
          {issues[0].message}
          {issues.length > 1 && ` 외 ${issues.length - 1}건`}
        </span>
      )}
      {state.save.phase === 'success' && <span className="mg-ok">저장했습니다</span>}
      {/* 서버 문장을 그대로 쓴다 (docs/08 §1.3-1) — SURVEY_LOCKED 처럼 사유를 알아야 다음
          행동이 정해지는 오류가 여기로 온다. 없을 때만 일반 문구다 */}
      {state.save.phase === 'error' && (
        <span className="sc-alert" role="alert">{state.save.errorMessage ?? '저장하지 못했습니다.'}</span>
      )}
      <button
        type="button"
        className="sc-btn sc-btn-primary"
        disabled={saving || issues.length > 0 || !state.dirty}
        onClick={() => void saveSurveyBuilder()}
      >
        {saving ? '저장 중...' : '설문 저장'}
      </button>
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
      {/* 화면을 열자마자 읽어야 할 것은 문항별 분포가 아니라 "얼마나 왔나" 다 */}
      <section className="mg-summary-row">
        <div className="mg-stat">
          <strong>{state.totalResponses}</strong>
          <span className="sc-note">전체 응답</span>
        </div>
        {/* spec 010 US2 시나리오 1 — 응답 수와 함께 최초·최근 응답 시각을 보인다 */}
        {state.firstRespondedAt !== null && state.lastRespondedAt !== null && (
          <>
            <div className="mg-stat">
              <strong>{respondedAt(state.firstRespondedAt)}</strong>
              <span className="sc-note">최초 응답</span>
            </div>
            <div className="mg-stat">
              <strong>{respondedAt(state.lastRespondedAt)}</strong>
              <span className="sc-note">최근 응답</span>
            </div>
          </>
        )}
      </section>
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
  const boothId = useManagementBoothId();
  const [tab, setTab] = useState<'builder' | 'result'>('builder');

  // 라우트가 :boothId 없이 매칭될 수 없지만, 숫자가 아닌 값이 오면 조회 경로가 조용히 깨진다 —
  // 합성 id 를 만들어 덮던 자리(`booth-${boothId ?? '1'}`)를 없앤 대신 여기서 드러낸다
  if (!Number.isSafeInteger(boothId) || boothId < 1) {
    return <ScreenError title="올바르지 않은 부스입니다" message="주소를 확인해 주세요." />;
  }

  return (
    <ManagementScreen
      title="설문 관리"
      subtitle="방문자에게 보여줄 설문을 만들고 응답을 확인합니다"
      actions={tab === 'builder' ? <BuilderSaveAction /> : undefined}
    >
      {/* 탭은 본문 맨 위다 — actions 는 footer 로 내려가므로 거기 두면 화면 전환이 바닥에 숨는다 */}
      <div className="mg-tabs" role="tablist">
        <button type="button" role="tab" aria-selected={tab === 'builder'} className={'mg-tab' + (tab === 'builder' ? ' mg-tab-on' : '')} onClick={() => setTab('builder')}>
          설문 편집
        </button>
        <button type="button" role="tab" aria-selected={tab === 'result'} className={'mg-tab' + (tab === 'result' ? ' mg-tab-on' : '')} onClick={() => setTab('result')}>
          응답 결과
        </button>
      </div>
      {tab === 'builder' ? <BuilderTab boothId={boothId} /> : <ResultTab boothId={boothId} />}
    </ManagementScreen>
  );
}
