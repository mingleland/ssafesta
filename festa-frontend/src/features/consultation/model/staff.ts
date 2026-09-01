// Staff 상담 상태 기계 (S15P21A604-375, spec 011 P1 — S15P21A604-138 화면의 데이터층).
// C-06: 직원은 동시에 활성 상담 1건 — 활성 상담이 있는 동안 수락은 게이트된다(서버도 거부하지만
// FE 가 먼저 막는다). Handoff Summary(AI 요약)는 카드·활성 세션에 그대로 노출한다.
import { useSyncExternalStore } from 'react';
import { consultationStaff } from '../../../entities/consultation/channel.select';
import type { ConsultationActiveSession, ConsultationRequestCard } from '../../../entities/consultation/channel.port';

export interface StaffConsultationState {
  status: 'idle' | 'loading' | 'ready' | 'error';
  queue: ConsultationRequestCard[];
  active: ConsultationActiveSession | null;
  accepting: boolean;
}

const initialState: StaffConsultationState = { status: 'idle', queue: [], active: null, accepting: false };

let state: StaffConsultationState = initialState;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<StaffConsultationState>): void {
  state = { ...state, ...patch };
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getStaffConsultationSnapshot(): StaffConsultationState {
  return state;
}

export function useStaffConsultation(): StaffConsultationState {
  return useSyncExternalStore(subscribe, getStaffConsultationSnapshot);
}

export async function loadStaffQueue(): Promise<void> {
  setState({ status: 'loading' });
  try {
    const queue = await consultationStaff.getQueue();
    setState({ status: 'ready', queue });
  } catch {
    setState({ status: 'error' });
  }
}

export function canAccept(): boolean {
  return state.status === 'ready' && state.active === null && !state.accepting; // C-06
}

export async function acceptRequest(requestId: string): Promise<void> {
  if (!canAccept()) return;
  setState({ accepting: true });
  try {
    const active = await consultationStaff.accept(requestId);
    setState({ active, queue: state.queue.filter((c) => c.requestId !== requestId), accepting: false });
  } catch {
    setState({ accepting: false });
  }
}

export async function endActiveConsultation(): Promise<void> {
  if (state.active === null) return;
  await consultationStaff.end();
  setState({ active: null });
}

export function __resetStaffConsultationForTests(): void {
  state = initialState;
  listeners.clear();
}
