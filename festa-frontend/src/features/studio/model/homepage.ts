// 소유자 홈페이지 등록 폼 상태 기계 (S15P21A604-374, spec 016 FR-001~FR-003).
// UI 폼 presentation 은 UI Track — 여기는 값·검증·저장/삭제·오류 상태만.
import { useSyncExternalStore } from 'react';
import { facadeApi } from '../../../entities/booth/facadeApi.select';
import { homepageApi } from '../../../entities/booth/homepageApi.select';
import { isApiError } from '../../../shared/api/client';

export type HomepageSaveErrorKind = 'invalid' | 'expired' | 'network';

export interface HomepageFormState {
  status: 'idle' | 'loading' | 'ready' | 'error';
  boothId: number | null;
  /** 서버에 저장된 값 — null 은 미등록 */
  saved: string | null;
  /** 입력 중 값. saved 와 다르면 dirty */
  value: string;
  save: { phase: 'idle' | 'submitting' | 'success' | 'error'; errorKind?: HomepageSaveErrorKind; errorMessage?: string };
}

const initialState: HomepageFormState = {
  status: 'idle',
  boothId: null,
  saved: null,
  value: '',
  save: { phase: 'idle' },
};

let state: HomepageFormState = initialState;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<HomepageFormState>): void {
  state = { ...state, ...patch };
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getHomepageFormSnapshot(): HomepageFormState {
  return state;
}

export function useHomepageForm(): HomepageFormState {
  return useSyncExternalStore(subscribe, getHomepageFormSnapshot);
}

export async function loadHomepageForm(boothId: number): Promise<void> {
  setState({ ...initialState, status: 'loading', boothId });
  try {
    const booth = await facadeApi.getBooth(boothId);
    if (state.boothId !== boothId) return; // 늦은 응답이 다른 부스 폼을 덮지 않는다 (-377)
    setState({ status: 'ready', saved: booth.homepageUrl, value: booth.homepageUrl ?? '' });
  } catch {
    if (state.boothId !== boothId) return;
    setState({ status: 'error' });
  }
}

export function setHomepageValue(value: string): void {
  // 제출 중 값 변경을 받으면 save.phase 리셋이 이중 제출 가드를 해제한다 (-377 공통 패턴) — 입력 차단
  if (state.save.phase === 'submitting') return;
  setState({ value, save: { phase: 'idle' } });
}

function saveErrorOf(e: unknown): { errorKind: HomepageSaveErrorKind; errorMessage?: string } {
  if (isApiError(e)) {
    // 최상위 code 는 VALIDATION_FAILED 이고 FIELD_INVALID 는 errors[].rule 어휘다
    // (BE ApiException.fieldInvalid — 독립 검증에서 오전사 발견, -377). 필드 규칙 위반의
    // 문구 정본은 서버(HttpUrlValidator) — FE 가 재발명하지 않는다.
    const fieldDetail = e.code === 'VALIDATION_FAILED' ? e.errors.find((d) => d.rule === 'FIELD_INVALID') : undefined;
    if (fieldDetail) return { errorKind: 'invalid', errorMessage: fieldDetail.message };
    if (e.code === 'BOOTH_LEASE_EXPIRED') return { errorKind: 'expired', errorMessage: e.message };
  }
  return { errorKind: 'network' };
}

export async function saveHomepage(): Promise<void> {
  if (state.save.phase === 'submitting' || state.boothId === null) return;
  const value = state.value.trim();
  // 빈 값 저장은 "등록 해제" 의도와 구분되지 않으므로 제출 자체를 막는다 — 해제는 clearHomepage 로만
  if (value === '') return;
  setState({ save: { phase: 'submitting' } });
  try {
    const saved = await homepageApi.putHomepage(state.boothId, value);
    setState({ saved: saved.homepageUrl, value: saved.homepageUrl ?? '', save: { phase: 'success' } });
  } catch (e) {
    setState({ save: { phase: 'error', ...saveErrorOf(e) } });
  }
}

/** 명시적 등록 해제 — PUT {homepageUrl: null} (키 생략과 다르다, BE PresenceField) */
export async function clearHomepage(): Promise<void> {
  if (state.save.phase === 'submitting' || state.boothId === null) return;
  setState({ save: { phase: 'submitting' } });
  try {
    await homepageApi.putHomepage(state.boothId, null);
    setState({ saved: null, value: '', save: { phase: 'success' } });
  } catch (e) {
    setState({ save: { phase: 'error', ...saveErrorOf(e) } });
  }
}

export function __resetHomepageFormForTests(): void {
  state = initialState;
  listeners.clear();
}
