// Survey 집계 결과 상태 기계 (S15P21A604-133) + 주관식 페이지네이션 (S15P21A604-194).
// UI 는 useSurveyResult() 와 loadNextTextPage 만 소비한다. 집계 화면 자체는 UI Track 후속.
import { useSyncExternalStore } from 'react';
import { isApiError } from '../../../shared/api/client';
import { surveyApi } from '../../../entities/survey/api.select';
import type { SurveyQuestionAggregateVM } from '../../../shared/contracts/survey';

export interface SurveyResultState {
  /**
   * noSurvey — 부스에 설문이 아직 없다. BE 는 `404 SURVEY_NOT_FOUND` 로 이 정상 상태를 알리는
   * 데(SurveyController — "오류 상태가 아니라 설문을 만들지 않았다는 뜻") 그것을 error 로
   * 렌더하면 설문 편집 탭이 멀쩡한데 결과만 실패처럼 보인다(2026-09-18 실측)
   */
  status: 'idle' | 'loading' | 'ready' | 'empty' | 'noSurvey' | 'error';
  /** 조회 기준. 늦은 응답 가드도 이 값으로 본다 */
  boothId: number | null;
  /** 결과 응답이 알려준 서버 id — 주관식 다음 페이지 조회에 쓴다 */
  surveyId: number | null;
  /** 전체 응답 수. 빈 판정의 기준이고 화면에도 표시한다 */
  totalResponses: number;
  /** 최초·최근 응답 시각 — wire ISO 원형. 포맷은 화면이 하고 null 은 null 로 둔다 */
  firstRespondedAt: string | null;
  lastRespondedAt: string | null;
  perQuestion: SurveyQuestionAggregateVM[];
  textAnswers: {
    // 누적 — loadNextTextPage 가 다음 페이지를 이어 붙인다.
    // questionId 는 서버 wire id 를 담은 string 이다(Builder 의 q-1 과 다른 공간)
    items: { questionId: string; text: string }[];
    page: number;
    hasNext: boolean;
    loadingNext: boolean;
  };
}

const initialState: SurveyResultState = {
  status: 'idle',
  boothId: null,
  surveyId: null,
  totalResponses: 0,
  firstRespondedAt: null,
  lastRespondedAt: null,
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

export async function loadSurveyResult(boothId: number): Promise<void> {
  setState({ ...initialState, status: 'loading', boothId });
  try {
    const result = await surveyApi.getResult(boothId);
    if (state.boothId !== boothId) return;
    // 빈 판정은 **응답 수**로 한다. perQuestion 은 응답이 0건이어도 모든 문항을 실어 오므로
    // (계약 §7) 길이로 보면 "아직 아무도 답하지 않았다" 가 영영 empty 로 걸리지 않는다
    const isEmpty = result.totalResponses === 0;
    setState({
      status: isEmpty ? 'empty' : 'ready',
      surveyId: result.surveyId,
      totalResponses: result.totalResponses,
      firstRespondedAt: result.firstRespondedAt,
      lastRespondedAt: result.lastRespondedAt,
      perQuestion: result.perQuestion,
      textAnswers: { ...result.textAnswers, loadingNext: false },
    });
  } catch (error) {
    if (state.boothId !== boothId) return;
    // 1단계(부스 설문 조회)의 404 SURVEY_NOT_FOUND 는 정상 상태다 — 결과 경로가 2단계라
    // (boothId → surveyId → results) 그 404가 여기까지 올라온다(#133 별칭 확정 전까지)
    if (isApiError(error) && error.code === 'SURVEY_NOT_FOUND') {
      setState({ status: 'noSurvey' });
      return;
    }
    setState({ status: 'error' });
  }
}

export async function loadNextTextPage(): Promise<void> {
  const { textAnswers, surveyId, boothId, status } = state;
  if (status !== 'ready' || surveyId === null || !textAnswers.hasNext || textAnswers.loadingNext) return;
  setState({ textAnswers: { ...textAnswers, loadingNext: true } });
  try {
    const next = await surveyApi.getTextAnswers(surveyId, textAnswers.page + 1);
    if (state.boothId !== boothId) return; // 설문 전환 후 도착한 이전 설문 페이지를 버린다 (-377)
    setState({
      textAnswers: {
        items: [...state.textAnswers.items, ...next.items],
        page: next.page,
        hasNext: next.hasNext,
        loadingNext: false,
      },
    });
  } catch {
    if (state.boothId !== boothId) return;
    // 다음 페이지 실패는 화면 전체를 무너뜨리지 않는다 — 로딩 플래그만 풀고 재시도 가능하게
    setState({ textAnswers: { ...state.textAnswers, loadingNext: false } });
  }
}

export function __resetSurveyResultForTests(): void {
  state = initialState;
  listeners.clear();
}
