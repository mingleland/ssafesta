// Survey 응답 상태 기계 (S15P21A604-368) — UI 는 useSurveyRun() 과 액션만 소비한다.
// 진입은 openOverlay('SURVEY', ...) intent 이후 — Unity SURVEY 이벤트 계약은 미정의라 배선 없음.
import { useSyncExternalStore } from 'react';
import { surveyApi } from '../../../entities/survey/api.select';
import { isEmptyAnswer } from '../../../entities/survey/mapper';
import type { SurveyAnswerValue, SurveyQuestionVM, SurveyRunStatus } from '../../../shared/contracts/survey';

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
  /** rewardedCoin = 실제 지급된 코인. 성공 전에는 null */
  submit: { phase: 'idle' | 'submitting' | 'success' | 'error'; rewardedCoin: number | null };
}

const initialState: SurveyRunState = {
  status: 'idle',
  boothId: null,
  surveyId: null,
  rewardCoin: 0,
  questions: [],
  answers: {},
  progress: { current: 0, total: 0 },
  submit: { phase: 'idle', rewardedCoin: null },
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
    submit: { phase: 'idle', rewardedCoin: null },
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
  setState({ submit: { phase: 'submitting', rewardedCoin: null } });
  try {
    const result = await surveyApi.submitAnswers(surveyId, state.answers);
    if (state.surveyId !== surveyId) return;
    setState({ submit: { phase: 'success', rewardedCoin: result.rewardedCoin } });
  } catch {
    if (state.surveyId !== surveyId) return;
    setState({ submit: { phase: 'error', rewardedCoin: null } });
  }
}

export function __resetSurveyRunForTests(): void {
  state = initialState;
  listeners.clear();
}
