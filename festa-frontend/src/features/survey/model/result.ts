// Survey 집계 결과 상태 기계 (S15P21A604-133) + 주관식 페이지네이션 (S15P21A604-194).
// UI 는 useSurveyResult() 와 loadNextTextPage 만 소비한다. 집계 화면 자체는 UI Track 후속.
import { useSyncExternalStore } from 'react';
import { surveyApi } from '../../../entities/survey/api.select';
import type { SurveyQuestionAggregateVM } from '../../../shared/contracts/survey';

export interface SurveyResultState {
  status: 'idle' | 'loading' | 'ready' | 'empty' | 'error';
  surveyId: string | null;
  perQuestion: SurveyQuestionAggregateVM[];
  textAnswers: {
    items: string[]; // 누적 — loadNextTextPage 가 다음 페이지를 이어 붙인다
    page: number;
    hasNext: boolean;
    loadingNext: boolean;
  };
}

const initialState: SurveyResultState = {
  status: 'idle',
  surveyId: null,
  perQuestion: [],
  textAnswers: { items: [], page: 0, hasNext: false, loadingNext: false },
};

let state: SurveyResultState = initialState;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<SurveyResultState>): void {
  state = { ...state, ...patch };
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getSurveyResultSnapshot(): SurveyResultState {
  return state;
}

export function useSurveyResult(): SurveyResultState {
  return useSyncExternalStore(subscribe, getSurveyResultSnapshot);
}

export async function loadSurveyResult(surveyId: string): Promise<void> {
  setState({ ...initialState, status: 'loading', surveyId });
  try {
    const result = await surveyApi.getResult(surveyId);
    if (state.surveyId !== surveyId) return;
    const isEmpty = result.perQuestion.length === 0 && result.textAnswers.items.length === 0;
    setState({
      status: isEmpty ? 'empty' : 'ready',
      perQuestion: result.perQuestion,
      textAnswers: { ...result.textAnswers, loadingNext: false },
    });
  } catch {
    if (state.surveyId !== surveyId) return;
    setState({ status: 'error' });
  }
}

export async function loadNextTextPage(): Promise<void> {
  const { textAnswers, surveyId, status } = state;
  if (status !== 'ready' || surveyId === null || !textAnswers.hasNext || textAnswers.loadingNext) return;
  setState({ textAnswers: { ...textAnswers, loadingNext: true } });
  try {
    const next = await surveyApi.getTextAnswers(surveyId, textAnswers.page + 1);
    setState({
      textAnswers: {
        items: [...state.textAnswers.items, ...next.items],
        page: next.page,
        hasNext: next.hasNext,
        loadingNext: false,
      },
    });
  } catch {
    // 다음 페이지 실패는 화면 전체를 무너뜨리지 않는다 — 로딩 플래그만 풀고 재시도 가능하게
    setState({ textAnswers: { ...state.textAnswers, loadingNext: false } });
  }
}

export function __resetSurveyResultForTests(): void {
  state = initialState;
  listeners.clear();
}
