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
  /** 마지막 동작 실패 — 조용히 삼키지 않는다(T-24 계열, -377). 다음 동작 시작 시 해제 */
  actionError: 'accept' | 'end' | null;
}

const initialState: StaffConsultationState = {
  status: 'idle',
  queue: [],
  active: null,
  accepting: false,
  actionError: null,
};

let state: StaffConsultationState = initialState;
const listeners = new Set<() => void>();
let queueLoadSeq = 0; // 늦은 큐 응답이 수락 이후 상태를 덮지 않게 하는 세대 토큰 (-377)

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
  const seq = ++queueLoadSeq;
  setState({ status: 'loading' });
  try {
    const queue = await consultationStaff.getQueue();
    if (seq !== queueLoadSeq) return; // 이후에 accept 등이 상태를 앞질렀다 — 낡은 목록을 버린다
    setState({ status: 'ready', queue });
  } catch {
    if (seq !== queueLoadSeq) return;
    setState({ status: 'error' });
  }
}

export function canAccept(): boolean {
  return state.status === 'ready' && state.active === null && !state.accepting; // C-06
}

export async function acceptRequest(requestId: string): Promise<void> {
  if (!canAccept()) return;
  queueLoadSeq++; // in-flight 큐 새로고침이 있었다면 그 응답을 무효화한다
  setState({ accepting: true, actionError: null });
  try {
    const active = await consultationStaff.accept(requestId);
    setState({ active, queue: state.queue.filter((c) => c.requestId !== requestId), accepting: false });
  } catch {
    setState({ accepting: false, actionError: 'accept' });
  }
}

export async function endActiveConsultation(): Promise<void> {
  if (state.active === null || state.accepting) return;
  setState({ accepting: true, actionError: null }); // 종료 중 재클릭·수락 중복 방지 플래그 겸용
  try {
    await consultationStaff.end();
    setState({ active: null, accepting: false });
  } catch {
    setState({ accepting: false, actionError: 'end' });
  }
}

export function __resetStaffConsultationForTests(): void {
  state = initialState;
  queueLoadSeq = 0;
  listeners.clear();
}
