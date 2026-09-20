// Survey Builder 상태 기계 (S15P21A604-369) — 문항 편집·검증·저장. UI editor 는 UI Track 소유.
// validation 은 FE 규칙으로 시작한다 — BE 검증(-190) 확정 시 Mapper 에서 정렬(FE 규칙은 상위 집합 유지).
import { useSyncExternalStore } from 'react';
import { isApiError } from '../../../shared/api/client';
import { surveyApi } from '../../../entities/survey/api.select';
import type {
  SurveyBuilderIssueVM,
  SurveyDraftVM,
  SurveyQuestionType,
  SurveyQuestionVM,
} from '../../../shared/contracts/survey';

export interface SurveyBuilderState {
  status: 'idle' | 'loading' | 'ready' | 'error';
  /** 편집 대상 부스. 저장 경로가 부스 기준이라(계약 §4) 로드 시점에 고정된다 */
  boothId: number | null;
  draft: SurveyDraftVM;
  dirty: boolean;
  /**
   * errorMessage 는 **서버가 준 사용자용 문장**이다 (`docs/08` §1.3-1). 화면이 그대로 쓴다 —
   * 버리면 `409 SURVEY_LOCKED`("응답이 있는 설문은 문항을 바꿀 수 없습니다")가 "저장하지
   * 못했습니다" 로 뭉개져 사용자가 사유를 알 수 없다 (S15P21A604-541).
   * `null` 이면 서버 문장이 없다는 뜻이고 그때만 화면이 일반 문구를 쓴다.
   */
  save: {
    phase: 'idle' | 'submitting' | 'success' | 'error';
    errorMessage: string | null;
    /** 서버가 `errors[0].field` 로 짚은 필드 — 화면이 그 입력 옆에 붙인다 (S15P21A604-520) */
    fieldError: { field: string; message: string } | null;
  };
}

const EMPTY_DRAFT: SurveyDraftVM = { title: '', rewardCoin: 0, questions: [] };
const NO_SAVE: SurveyBuilderState['save'] = { phase: 'idle', errorMessage: null, fieldError: null };

const initialState: SurveyBuilderState = {
  status: 'idle',
  boothId: null,
  draft: EMPTY_DRAFT,
  dirty: false,
  save: NO_SAVE,
};

let state: SurveyBuilderState = initialState;
let questionSeq = 0;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<SurveyBuilderState>): void {
  state = { ...state, ...patch };
  emit();
}

function editDraft(mutate: (draft: SurveyDraftVM) => SurveyDraftVM): void {
  if (state.status !== 'ready') return;
  // 저장 중 편집은 save.phase 리셋으로 이중 저장 가드를 해제한다 (-377 공통 패턴) — 차단
  if (state.save.phase === 'submitting') return;
  setState({ draft: mutate(state.draft), dirty: true, save: NO_SAVE });
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getSurveyBuilderSnapshot(): SurveyBuilderState {
  return state;
}

export function useSurveyBuilder(): SurveyBuilderState {
  return useSyncExternalStore(subscribe, getSurveyBuilderSnapshot);
}

export async function loadSurveyBuilder(boothId: number): Promise<void> {
  setState({ ...initialState, status: 'loading', boothId });
  try {
    const draft = await surveyApi.getDraft(boothId);
    // 리로드 후 seq 가 0 부터 다시 시작하면 저장된 문항의 q-N 과 충돌한다 (-377) —
    // 복원된 id 의 최댓값 뒤에서 이어 발급한다
    questionSeq = Math.max(
      questionSeq,
      ...(draft?.questions ?? []).map((q) => Number(/^q-(\d+)$/.exec(q.id)?.[1] ?? 0)),
    );
    if (state.boothId !== boothId) return; // 부스를 옮긴 뒤 도착한 이전 응답은 버린다
    setState({ status: 'ready', draft: draft ?? EMPTY_DRAFT, dirty: false });
  } catch {
    if (state.boothId !== boothId) return;
    setState({ status: 'error' });
  }
}

/** 유형별 최소 골격으로 문항을 추가한다 — 옵션 2개·1~5 척도는 FE validation 통과 가능한 시작값 */
export function addQuestion(type: SurveyQuestionType): void {
  editDraft((draft) => {
    const question: SurveyQuestionVM = {
      id: `q-${++questionSeq}`,
      type,
      prompt: '',
      required: false,
      ...(type === 'single' || type === 'multi'
        ? { options: [{ id: 'o-1', label: '' }, { id: 'o-2', label: '' }] }
        : {}),
      ...(type === 'rating' ? { scale: { min: 1, max: 5 } } : {}),
    };
    return { ...draft, questions: [...draft.questions, question] };
  });
}

export function removeQuestion(id: string): void {
  editDraft((draft) => ({ ...draft, questions: draft.questions.filter((q) => q.id !== id) }));
}

export function reorderQuestion(from: number, to: number): void {
  editDraft((draft) => {
    const { questions } = draft;
    if (from < 0 || from >= questions.length || to < 0 || to >= questions.length || from === to) return draft;
    const next = [...questions];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    return { ...draft, questions: next };
  });
}

export function updateQuestion(id: string, patch: Partial<Omit<SurveyQuestionVM, 'id' | 'type'>>): void {
  editDraft((draft) => ({
    ...draft,
    questions: draft.questions.map((q) => (q.id === id ? { ...q, ...patch } : q)),
  }));
}

export function updateTitle(title: string): void {
  editDraft((draft) => ({ ...draft, title }));
}

/** 0 = 보상 없음. 상한은 서버 설정이라 FE 가 정하지 않는다 — 초과는 저장 시 400 이 필드로 돌아온다 */
export function updateRewardCoin(rewardCoin: number): void {
  editDraft((draft) => ({ ...draft, rewardCoin }));
}

/** FE 규칙: 제목·문항 비공백, 선택형 옵션 최소 2, rating min<max, 보상 코인은 0 이상의 정수 */
export function validateBuilder(): SurveyBuilderIssueVM[] {
  const issues: SurveyBuilderIssueVM[] = [];
  const { draft } = state;
  if (draft.title.trim() === '') issues.push({ message: '설문 제목을 입력해주세요.' });
  if (!Number.isInteger(draft.rewardCoin) || draft.rewardCoin < 0) {
    issues.push({ message: '보상 코인은 0 이상의 정수여야 합니다.' });
  }
  for (const q of draft.questions) {
    if (q.prompt.trim() === '') issues.push({ questionId: q.id, message: '질문 내용을 입력해주세요.' });
    if (q.type === 'single' || q.type === 'multi') {
      const options = q.options ?? [];
      if (options.length < 2) {
        issues.push({ questionId: q.id, message: '선택지는 2개 이상 필요합니다.' });
      } else if (options.some((o) => o.label.trim() === '')) {
        // 비공백 2개만 세면 뒤에 붙은 빈 선택지가 그대로 서버로 가고, 계약 §4 가 모든 label 비공백을
        // 요구하므로 진단 불가능한 400 이 된다. FE 검증은 BE 의 상위 집합이어야 한다
        issues.push({ questionId: q.id, message: '빈 선택지가 있습니다. 내용을 채우거나 지워주세요.' });
      }
    }
    if (q.type === 'rating' && q.scale && q.scale.min >= q.scale.max) {
      issues.push({ questionId: q.id, message: '척도 최솟값은 최댓값보다 작아야 합니다.' });
    }
  }
  return issues;
}

export async function saveSurveyBuilder(): Promise<void> {
  if (state.status !== 'ready' || state.boothId === null) return;
  if (state.save.phase === 'submitting' || validateBuilder().length > 0) return;
  // 저장 시작 시점의 부스를 잡아 둔다. await 뒤에 대조하지 않으면 저장 중 부스를 옮겼을 때
  // 이전 부스의 늦은 응답이 **새 부스의 dirty 를 해제**해 미저장 변경이 사라진다
  // (GitLab #133, 2026-09-08 BE 지적). run.ts 의 submitSurveyRun 과 같은 모양이다
  const boothId = state.boothId;
  setState({ save: { ...NO_SAVE, phase: 'submitting' } });
  try {
    await surveyApi.saveDraft(boothId, state.draft);
    if (state.boothId !== boothId) return;
    setState({ dirty: false, save: { ...NO_SAVE, phase: 'success' } });
  } catch (error) {
    if (state.boothId !== boothId) return;
    // errors[0].field 가 있으면 그 필드의 오류다 — 상한 초과 rewardCoin 이 여기로 온다 (GitLab #133)
    const detail = isApiError(error) ? error.errors.find((e) => e.field !== undefined) : undefined;
    setState({
      save: {
        phase: 'error',
        errorMessage: isApiError(error) ? error.message : null,
        fieldError: detail?.field !== undefined ? { field: detail.field, message: detail.message } : null,
      },
    });
  }
}

export function __resetSurveyBuilderForTests(): void {
  state = initialState;
  questionSeq = 0;
  listeners.clear();
}
