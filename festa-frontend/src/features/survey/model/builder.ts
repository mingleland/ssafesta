// Survey Builder 상태 기계 (S15P21A604-369) — 문항 편집·검증·저장. UI editor 는 UI Track 소유.
// validation 은 FE 규칙으로 시작한다 — BE 검증(-190) 확정 시 Mapper 에서 정렬(FE 규칙은 상위 집합 유지).
import { useSyncExternalStore } from 'react';
import { surveyApi } from '../../../entities/survey/api.select';
import type {
  SurveyBuilderIssueVM,
  SurveyDraftVM,
  SurveyQuestionType,
  SurveyQuestionVM,
} from '../../../shared/contracts/survey';

export interface SurveyBuilderState {
  status: 'idle' | 'loading' | 'ready' | 'error';
  draft: SurveyDraftVM;
  dirty: boolean;
  save: { phase: 'idle' | 'submitting' | 'success' | 'error' };
}

const EMPTY_DRAFT: SurveyDraftVM = { title: '', questions: [] };

const initialState: SurveyBuilderState = {
  status: 'idle',
  draft: EMPTY_DRAFT,
  dirty: false,
  save: { phase: 'idle' },
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
  setState({ draft: mutate(state.draft), dirty: true, save: { phase: 'idle' } });
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

export async function loadSurveyBuilder(): Promise<void> {
  setState({ ...initialState, status: 'loading' });
  try {
    const draft = await surveyApi.getDraft();
    // 리로드 후 seq 가 0 부터 다시 시작하면 저장된 문항의 q-N 과 충돌한다 (-377) —
    // 복원된 id 의 최댓값 뒤에서 이어 발급한다
    questionSeq = Math.max(
      questionSeq,
      ...(draft?.questions ?? []).map((q) => Number(/^q-(\d+)$/.exec(q.id)?.[1] ?? 0)),
    );
    setState({ status: 'ready', draft: draft ?? EMPTY_DRAFT, dirty: false });
  } catch {
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

/** FE 규칙: 제목·문항 비공백, 선택형 옵션 최소 2, rating min<max */
export function validateBuilder(): SurveyBuilderIssueVM[] {
  const issues: SurveyBuilderIssueVM[] = [];
  const { draft } = state;
  if (draft.title.trim() === '') issues.push({ message: '설문 제목을 입력해주세요.' });
  for (const q of draft.questions) {
    if (q.prompt.trim() === '') issues.push({ questionId: q.id, message: '질문 내용을 입력해주세요.' });
    if ((q.type === 'single' || q.type === 'multi') && (q.options ?? []).filter((o) => o.label.trim() !== '').length < 2) {
      issues.push({ questionId: q.id, message: '선택지는 2개 이상 필요합니다.' });
    }
    if (q.type === 'rating' && q.scale && q.scale.min >= q.scale.max) {
      issues.push({ questionId: q.id, message: '척도 최솟값은 최댓값보다 작아야 합니다.' });
    }
  }
  return issues;
}

export async function saveSurveyBuilder(): Promise<void> {
  if (state.status !== 'ready' || state.save.phase === 'submitting' || validateBuilder().length > 0) return;
  setState({ save: { phase: 'submitting' } });
  try {
    await surveyApi.saveDraft(state.draft);
    setState({ dirty: false, save: { phase: 'success' } });
  } catch {
    setState({ save: { phase: 'error' } });
  }
}

export function __resetSurveyBuilderForTests(): void {
  state = initialState;
  questionSeq = 0;
  listeners.clear();
}
