// Survey 응답 상태 기계 (S15P21A604-368) — UI 는 useSurveyRun() 과 액션만 소비한다.
// 진입은 openOverlay('SURVEY', ...) intent 이후 — Unity SURVEY 이벤트 계약은 미정의라 배선 없음.
import { useSyncExternalStore } from 'react';
import { isApiError } from '../../../shared/api/client';
import { surveyApi } from '../../../entities/survey/api.select';
import { isEmptyAnswer } from '../../../entities/survey/mapper';
import type { SurveyAnswerValue, SurveyQuestionVM, SurveyRunStatus } from '../../../shared/contracts/survey';

/**
 * 서버가 준 사용자용 문장만 꺼낸다. 오류 봉투가 아니면(네트워크 실패 등) `null` 이고
 * 그때는 화면이 일반 문구를 쓴다 — 없는 문장을 지어내지 않는다.
 */
function userMessageOf(error: unknown): string | null {
  return isApiError(error) ? error.message : null;
}

export interface SurveyRunState {
  status: SurveyRunStatus;
  /** 진입 기준 — 화면이 아는 것은 부스이고 설문 id 는 서버가 알려준다 */
  boothId: number | null;
  /** run 응답이 준 서버 id. 제출에 쓴다 — 로드 전에는 null */
  surveyId: number | null;
  /** 0 보다 크면 게스트는 제출할 수 없다 (403 MEMBER_ONLY) — 실패 전에 안내한다 */
  rewardCoin: number;
  questions: SurveyQuestionVM[];
  answers: Record<string, SurveyAnswerValue>;
  progress: { current: number; total: number };
  /**
   * rewardedCoin = 실제 지급된 코인. 성공 전에는 null.
   *
   * errorMessage 는 **서버가 준 사용자용 문장**이다. `docs/08` §1.3-1 이
   * *"사용자에게 보여줄 문장은 봉투 최상위 message 가 담는다"* 로 규정한 그 값이라 화면이
   * 그대로 쓴다. 이것을 버리면 `409 SURVEY_ALREADY_RESPONDED`("이미 응답한 설문입니다")가
   * "다시 시도해 주세요" 로 뭉개져 **재시도해도 성공하지 않는 오류에 재시도를 권하게 된다**
   * (S15P21A604-541). `null` 은 서버 문장이 없다는 뜻이고 그때만 화면이 일반 문구를 쓴다.
   */
  submit: {
    phase: 'idle' | 'submitting' | 'success' | 'error';
    rewardedCoin: number | null;
    errorMessage: string | null;
  };
}

const initialState: SurveyRunState = {
  status: 'idle',
  boothId: null,
  surveyId: null,
  rewardCoin: 0,
  questions: [],
  answers: {},
  progress: { current: 0, total: 0 },
  submit: { phase: 'idle', rewardedCoin: null, errorMessage: null },
};

let state: SurveyRunState = initialState;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<SurveyRunState>): void {
  state = { ...state, ...patch };
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getSurveyRunSnapshot(): SurveyRunState {
  return state;
}

export function useSurveyRun(): SurveyRunState {
  return useSyncExternalStore(subscribe, getSurveyRunSnapshot);
}

export async function loadSurveyRun(boothId: number): Promise<void> {
  setState({ ...initialState, status: 'loading', boothId });
  try {
    const run = await surveyApi.getRun(boothId);
    if (state.boothId !== boothId) return; // 늦은 응답 가드 — 부스 기준으로 본다
    const status: SurveyRunStatus =
      run.status === 'closed' ? 'closed' : run.questions.length === 0 ? 'empty' : 'ready';
    setState({
      status,
      surveyId: run.surveyId,
      rewardCoin: run.rewardCoin,
      questions: run.questions,
      progress: { current: 0, total: run.questions.length },
    });
  } catch {
    if (state.boothId !== boothId) return;
    setState({ status: 'error' });
  }
}

export function setAnswer(questionId: string, value: SurveyAnswerValue): void {
  if (state.status !== 'ready') return;
  // 제출 중 답 변경은 submit.phase 리셋으로 이중 제출 가드를 해제한다 (-377 공통 패턴) — 차단
  if (state.submit.phase === 'submitting') return;
  const answers = { ...state.answers, [questionId]: value };
  setState({
    answers,
    progress: { current: Object.keys(answers).length, total: state.questions.length },
    submit: { phase: 'idle', rewardedCoin: null, errorMessage: null },
  });
}

// isEmptyAnswer 는 entities/survey/mapper 로 옮겼다 — 계약 §6 이 "서버도 FE 와 같은 판정" 이라고
// 명시하므로, 제출 본문을 만드는 쪽과 required 를 검사하는 쪽이 같은 정의를 봐야 한다.

/** 미응답 required 질문 id — 비어야 제출 가능하다 */
export function missingRequired(): string[] {
  return state.questions
    .filter((q) => {
      if (!q.required) return false;
      const answer = state.answers[q.id];
      return answer === undefined || isEmptyAnswer(answer);
    })
    .map((q) => q.id);
}

export function canSubmit(): boolean {
  return state.status === 'ready' && state.submit.phase !== 'submitting' && missingRequired().length === 0;
}

export async function submitSurveyRun(): Promise<void> {
  if (!canSubmit() || state.surveyId === null) return;
  const surveyId = state.surveyId;
  setState({ submit: { phase: 'submitting', rewardedCoin: null, errorMessage: null } });
  try {
    const result = await surveyApi.submitAnswers(surveyId, state.answers);
    if (state.surveyId !== surveyId) return;
    setState({ submit: { phase: 'success', rewardedCoin: result.rewardedCoin, errorMessage: null } });
  } catch (error) {
    if (state.surveyId !== surveyId) return;
    setState({
      submit: { phase: 'error', rewardedCoin: null, errorMessage: userMessageOf(error) },
    });
  }
}

export function __resetSurveyRunForTests(): void {
  state = initialState;
  listeners.clear();
}
