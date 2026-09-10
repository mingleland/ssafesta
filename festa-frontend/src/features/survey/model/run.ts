// Survey 응답 상태 기계 (S15P21A604-368) — UI 는 useSurveyRun() 과 액션만 소비한다.
// 진입은 openOverlay('SURVEY', source) 하나다. source 가 부스인지 이벤트인지만 다르고(-608)
// 문항 렌더·검증·제출·상태 전이는 두 경로가 같은 것을 쓴다.
import { useSyncExternalStore } from 'react';
import { isApiError } from '../../../shared/api/client';
import { surveyApi } from '../../../entities/survey/api.select';
import { isEmptyAnswer } from '../../../entities/survey/mapper';
import { isSameSurveySource } from '../../../shared/contracts/survey';
import type {
  SurveyAnswerValue,
  SurveyQuestionVM,
  SurveyRunStatus,
  SurveySource,
} from '../../../shared/contracts/survey';

/**
 * 서버가 준 사용자용 문장만 꺼낸다. 오류 봉투가 아니면(네트워크 실패 등) `null` 이고
 * 그때는 화면이 일반 문구를 쓴다 — 없는 문장을 지어내지 않는다.
 */
function userMessageOf(error: unknown): string | null {
  return isApiError(error) ? error.message : null;
}

export interface SurveyRunState {
  status: SurveyRunStatus;
  /**
   * 진입 기준 (S15P21A604-608). 부스 설문은 부스가, 이벤트 설문은 surveyKey 가 열쇠이고
   * **설문 id 는 어느 쪽이든 서버가 알려준다.** 늦은 응답 가드도 이 값으로 본다 — 두 경로가
   * 같은 스토어를 쓰므로(Overlay Bus 는 한 번에 하나만 연다) 교차 진입도 여기서 걸러진다.
   */
  source: SurveySource | null;
  /** run 응답이 준 서버 id. 제출에 쓴다 — 로드 전에는 null */
  surveyId: number | null;
  /** 0 보다 크면 게스트는 제출할 수 없다 (403 MEMBER_ONLY) — 실패 전에 안내한다 */
  rewardCoin: number;
  /**
   * 보상과 무관하게 회원 전용인가. 이벤트 설문이 그렇다 — 보상이 0 인데도 추첨 때문에 참여자를
   * 특정해야 한다. `rewardCoin > 0` 하나로 판정하면 이 설문이 게스트에게 열려 버린다.
   */
  memberOnly: boolean;
  /** 이미 참여한 시각. null 이 아니면 재참여가 없다 — 화면은 제출 완료와 같은 자리를 쓴다 */
  respondedAt: string | null;
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
  source: null,
  surveyId: null,
  rewardCoin: 0,
  memberOnly: false,
  respondedAt: null,
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

/**
 * 설문을 연다. **부스든 이벤트든 같은 함수다** (S15P21A604-608) — 갈리는 것은 어느 어댑터를
 * 부르는가 한 줄이고, 그 뒤의 문항·검증·제출·상태 전이는 하나다.
 */
export async function loadSurveyRun(source: SurveySource): Promise<void> {
  setState({ ...initialState, status: 'loading', source });
  try {
    const run =
      source.kind === 'booth'
        ? await surveyApi.getRun(source.boothId)
        : await surveyApi.getEventRun(source.surveyKey);
    if (!isSameSurveySource(state.source, source)) return; // 늦은 응답 가드 — 진입 기준으로 본다
    const status: SurveyRunStatus =
      run.status === 'closed' ? 'closed' : run.questions.length === 0 ? 'empty' : 'ready';
    setState({
      status,
      surveyId: run.surveyId,
      rewardCoin: run.rewardCoin,
      memberOnly: run.memberOnly,
      respondedAt: run.responded?.submittedAt ?? null,
      questions: run.questions,
      progress: { current: 0, total: run.questions.length },
    });
  } catch {
    if (!isSameSurveySource(state.source, source)) return;
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
  // 이미 참여했으면 제출하지 않는다 — 서버는 409 로 막지만, 눌러서 실패하는 버튼을 두지 않는다
  return (
    state.status === 'ready' &&
    state.respondedAt === null &&
    state.submit.phase !== 'submitting' &&
    missingRequired().length === 0
  );
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
