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
    setState({ status: 'ready', saved: booth.homepageUrl, value: booth.homepageUrl ?? '' });
  } catch {
    setState({ status: 'error' });
  }
}

export function setHomepageValue(value: string): void {
  setState({ value, save: { phase: 'idle' } });
}

function saveErrorOf(e: unknown): { errorKind: HomepageSaveErrorKind; errorMessage?: string } {
  if (isApiError(e)) {
    if (e.code === 'FIELD_INVALID') {
      // 서버 문구가 형식 규칙의 정본이다(HttpUrlValidator) — FE 가 문구를 재발명하지 않는다
      return { errorKind: 'invalid', errorMessage: e.errors[0]?.message ?? e.message };
    }
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
