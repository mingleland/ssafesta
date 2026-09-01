// 방문자 상담 상태 기계 (S15P21A604-375, spec 011 P1).
// C-01: 만료 10분 — FE 는 잔여 시간을 안내하고 만료 시 재요청 버튼을 제공한다. 만료의 정본은
// 서버 이벤트지만, 로컬 카운트다운이 0 에 닿으면 같은 전환을 먼저 보여준다(둘 중 먼저 온 쪽).
// C-04: 게스트 불가 — 진입점(UI)에서 미노출이 정책이라 이 모델은 게스트 여부를 다시 검사하지 않는다.
// 실시간 메시지 송수신은 P2(C-02) — 이 모델에 없다.
import { useSyncExternalStore } from 'react';
import { consultationChannel } from '../../../entities/consultation/channel.select';
import type { VisitorChannelEvent } from '../../../entities/consultation/channel.port';

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

// 채널 구독은 서버가 세션의 끝을 말할 때(ended, 서버 expired)까지 유지한다 (-377).
// - accepted 에서 끊으면 이후 'ended' 가 유실돼 방문자가 active 에 갇힌다
// - 로컬 카운트다운 만료에서 끊으면 직전에 전송된 'accepted' 가 유실된다 — 만료의 정본은
//   서버(C-01)이고 로컬 0 도달은 표시 선반영일 뿐이므로, 그 뒤 도착한 서버 이벤트가 이긴다
function onChannelEvent(event: VisitorChannelEvent): void {
  if (event.type === 'accepted') {
    stopCountdown();
    setState({ phase: 'active', remainingSeconds: null, staffName: event.staffName });
  } else if (event.type === 'expired') {
    stopCountdown();
    detachChannel();
    setState({ phase: 'expired', remainingSeconds: null, staffName: null });
  } else {
    stopCountdown();
    detachChannel();
    setState({ phase: 'ended', remainingSeconds: null });
  }
}

export async function requestConsultation(boothId: number): Promise<void> {
  if (state.phase === 'requesting' || state.phase === 'waiting' || state.phase === 'active') return;
  detachChannel(); // 로컬 만료(expired) 상태에서 재요청하면 기존 구독을 먼저 정리한다
  setState({ phase: 'requesting', boothId, remainingSeconds: null, staffName: null });
  try {
    const { expiresInSeconds } = await consultationChannel.requestConsultation(boothId);
    unsubscribeChannel = consultationChannel.onVisitorEvent(onChannelEvent);
    setState({ phase: 'waiting', remainingSeconds: expiresInSeconds });
    countdown = setInterval(() => {
      const remaining = (state.remainingSeconds ?? 0) - 1;
      if (remaining <= 0) {
        // 로컬 만료 — 표시만 전환하고 구독은 유지한다(서버 accepted/expired 가 최종 판정)
        stopCountdown();
        setState({ phase: 'expired', remainingSeconds: null });
      } else {
        setState({ remainingSeconds: remaining });
      }
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
