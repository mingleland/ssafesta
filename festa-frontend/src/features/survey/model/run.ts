// Survey 응답 상태 기계 (S15P21A604-368) — UI 는 useSurveyRun() 과 액션만 소비한다.
// 진입은 openOverlay('SURVEY', ...) intent 이후 — Unity SURVEY 이벤트 계약은 미정의라 배선 없음.
import { useSyncExternalStore } from 'react';
import { surveyApi } from '../../../entities/survey/api.select';
import type { SurveyAnswerValue, SurveyQuestionVM, SurveyRunStatus } from '../../../shared/contracts/survey';

export interface SurveyRunState {
  status: SurveyRunStatus;
  surveyId: string | null;
  questions: SurveyQuestionVM[];
  answers: Record<string, SurveyAnswerValue>;
  progress: { current: number; total: number };
  submit: { phase: 'idle' | 'submitting' | 'success' | 'error' };
}

const initialState: SurveyRunState = {
  status: 'idle',
  surveyId: null,
  questions: [],
  answers: {},
  progress: { current: 0, total: 0 },
  submit: { phase: 'idle' },
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

export async function loadSurveyRun(surveyId: string): Promise<void> {
  setState({ ...initialState, status: 'loading', surveyId });
  try {
    const run = await surveyApi.getRun(surveyId);
    if (state.surveyId !== surveyId) return; // 늦은 응답 가드
    const status: SurveyRunStatus =
      run.status === 'closed' ? 'closed' : run.questions.length === 0 ? 'empty' : 'ready';
    setState({ status, questions: run.questions, progress: { current: 0, total: run.questions.length } });
  } catch {
    if (state.surveyId !== surveyId) return;
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
    submit: { phase: 'idle' },
  });
}

// 키 존재만으로는 required 를 채운 것이 아니다 — 빈 선택·공백 텍스트는 미응답이다 (-377)
function isEmptyAnswer(value: SurveyAnswerValue): boolean {
  switch (value.type) {
    case 'multi':
      return value.optionIds.length === 0;
    case 'short_text':
    case 'long_text':
    case 'application':
      return value.text.trim() === '';
    default:
      return false;
  }
}

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
  setState({ submit: { phase: 'submitting' } });
  try {
    await surveyApi.submitAnswers(state.surveyId, state.answers);
    setState({ submit: { phase: 'success' } });
  } catch {
    setState({ submit: { phase: 'error' } });
  }
}

export function __resetSurveyRunForTests(): void {
  state = initialState;
  listeners.clear();
}
