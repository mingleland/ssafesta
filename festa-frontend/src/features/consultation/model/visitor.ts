// 방문자 상담 상태 기계 (S15P21A604-375, spec 011 P1).
// C-01: 만료 10분 — FE 는 잔여 시간을 안내하고 만료 시 재요청 버튼을 제공한다. 만료의 정본은
// 서버 이벤트지만, 로컬 카운트다운이 0 에 닿으면 같은 전환을 먼저 보여준다(둘 중 먼저 온 쪽).
// C-04: 게스트 불가 — 진입점(UI)에서 미노출이 정책이라 이 모델은 게스트 여부를 다시 검사하지 않는다.
// 실시간 메시지 송수신은 P2(C-02) — 이 모델에 없다.
import { useSyncExternalStore } from 'react';
import { consultationChannel } from '../../../entities/consultation/channel.select';

export type VisitorConsultationPhase =
  | 'idle'
  | 'requesting'
  | 'waiting'
  | 'expired'
  | 'active'
  | 'ended'
  | 'error';

export interface VisitorConsultationState {
  phase: VisitorConsultationPhase;
  boothId: number | null;
  /** waiting 전용 — C-01 잔여 시간 안내 */
  remainingSeconds: number | null;
  /** active 전용 */
  staffName: string | null;
}

const initialState: VisitorConsultationState = { phase: 'idle', boothId: null, remainingSeconds: null, staffName: null };

let state: VisitorConsultationState = initialState;
const listeners = new Set<() => void>();
let countdown: ReturnType<typeof setInterval> | null = null;
let unsubscribeChannel: (() => void) | null = null;

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<VisitorConsultationState>): void {
  state = { ...state, ...patch };
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getVisitorConsultationSnapshot(): VisitorConsultationState {
  return state;
}

export function useVisitorConsultation(): VisitorConsultationState {
  return useSyncExternalStore(subscribe, getVisitorConsultationSnapshot);
}

function stopCountdown(): void {
  if (countdown !== null) clearInterval(countdown);
  countdown = null;
}

function detachChannel(): void {
  unsubscribeChannel?.();
  unsubscribeChannel = null;
}

function settle(phase: 'expired' | 'active' | 'ended', staffName: string | null = null): void {
  stopCountdown();
  detachChannel();
  setState({ phase, remainingSeconds: null, staffName });
}

export async function requestConsultation(boothId: number): Promise<void> {
  if (state.phase === 'requesting' || state.phase === 'waiting' || state.phase === 'active') return;
  setState({ phase: 'requesting', boothId, remainingSeconds: null, staffName: null });
  try {
    const { expiresInSeconds } = await consultationChannel.requestConsultation(boothId);
    unsubscribeChannel = consultationChannel.onVisitorEvent((event) => {
      if (event.type === 'accepted') settle('active', event.staffName);
      else if (event.type === 'expired') settle('expired');
      else settle('ended');
    });
    setState({ phase: 'waiting', remainingSeconds: expiresInSeconds });
    countdown = setInterval(() => {
      const remaining = (state.remainingSeconds ?? 0) - 1;
      if (remaining <= 0) settle('expired');
      else setState({ remainingSeconds: remaining });
    }, 1_000);
  } catch {
    setState({ phase: 'error' });
  }
}

/** C-01 — expired 전용 재요청 */
export async function rerequestConsultation(): Promise<void> {
  if (state.phase !== 'expired' || state.boothId === null) return;
  const boothId = state.boothId;
  setState({ phase: 'idle' });
  await requestConsultation(boothId);
}

export async function cancelConsultation(): Promise<void> {
  if (state.phase !== 'waiting') return;
  stopCountdown();
  detachChannel();
  try {
    await consultationChannel.cancelRequest();
  } finally {
    setState({ phase: 'idle', remainingSeconds: null });
  }
}

export function __resetVisitorConsultationForTests(): void {
  stopCountdown();
  detachChannel();
  state = initialState;
  listeners.clear();
}
