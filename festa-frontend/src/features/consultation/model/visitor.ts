// 방문자 상담 상태 기계 (S15P21A604-375, spec 011 P1).
// C-01: 만료 10분 — FE 는 잔여 시간을 안내하고 만료 시 재요청 버튼을 제공한다. 만료의 정본은
// 서버 이벤트지만, 로컬 카운트다운이 0 에 닿으면 같은 전환을 먼저 보여준다(둘 중 먼저 온 쪽).
// C-04: 게스트 불가 — 진입점(UI)에서 미노출이 정책이라 이 모델은 게스트 여부를 다시 검사하지 않는다.
// 실시간 메시지 송수신은 P2(C-02) — 이 모델에 없다.
import { useSyncExternalStore } from 'react';
import { consultationChannel } from '../../../entities/consultation/channel.select';
import type { VisitorChannelEvent } from '../../../entities/consultation/channel.port';
import type { ConsultationStartContext, ConsultationStartSource } from './startContext';

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
  /**
   * 이 상담이 어디서 시작됐는가 (S15P21A604-416). 표시용이 아니라 경계 기록이다 —
   * transport(G-7)가 요청 payload 를 만들 때, 그리고 진입점이 늘었을 때 어떤 경로로 들어온
   * 상담인지 구분해야 한다. idle 이면 null.
   */
  source: ConsultationStartSource | null;
  boothId: number | null;
  /**
   * 요약 재료가 된 AI 대화 id. `source` 와 같은 성격의 경계 기록이다 — 표시용이 아니라
   * 재요청(C-01)이 같은 대화 맥락을 다시 실어 보내기 위한 값이다. 대화가 이미 닫혔으면
   * 서버가 요약을 못 만들어 `null` 이 되지만, 그건 요청을 막지 않는다(#133).
   */
  conversationId: string | null;
  /** waiting 전용 — C-01 잔여 시간 안내 */
  remainingSeconds: number | null;
  /** active 전용 */
  staffName: string | null;
}

const initialState: VisitorConsultationState = {
  phase: 'idle',
  boothId: null,
  conversationId: null,
  source: null,
  remainingSeconds: null,
  staffName: null,
};

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

/**
 * 상담을 요청한다. 대상 부스와 진입 경로는 호출자가 `ConsultationStartContext` 로 준다 —
 * 이 상태 기계는 "어디서 왔는지" 를 스스로 판단하지 않는다(S15P21A604-416).
 *
 * 게스트 차단(spec 011 FR-014·C-04)은 진입점 UI 의 몫이다. 여기서 다시 검사하지 않는 것은
 * 기존 정책 그대로다.
 */
export async function requestConsultation(context: ConsultationStartContext): Promise<void> {
  if (state.phase === 'requesting' || state.phase === 'waiting' || state.phase === 'active') return;
  const { boothId, source, conversationId } = context;
  detachChannel(); // 로컬 만료(expired) 상태에서 재요청하면 기존 구독을 먼저 정리한다
  setState({
    phase: 'requesting',
    boothId,
    source,
    conversationId: conversationId ?? null,
    remainingSeconds: null,
    staffName: null,
  });
  try {
    // 요청 body 는 { boothId, conversationId? } 다 (#133 확정). 요약 텍스트는 싣지 않는다 —
    // 서버가 conversationId 로 FastAPI 에 요약을 청해 생성 시점 스냅샷으로 굳힌다.
    const { expiresInSeconds } = await consultationChannel.requestConsultation(boothId, conversationId);
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

/** C-01 — expired 전용 재요청. 대상 부스와 경로는 처음 요청 때 것을 그대로 쓴다 */
export async function rerequestConsultation(): Promise<void> {
  if (state.phase !== 'expired' || state.boothId === null) return;
  const boothId = state.boothId;
  const source = state.source ?? 'AI_HANDOFF';
  const conversationId = state.conversationId ?? undefined;
  setState({ phase: 'idle' });
  await requestConsultation({ boothId, source, conversationId });
}

export async function cancelConsultation(): Promise<void> {
  if (state.phase !== 'waiting') return;
  stopCountdown();
  detachChannel();
  try {
    await consultationChannel.cancelRequest();
  } finally {
    setState({ phase: 'idle', source: null, conversationId: null, remainingSeconds: null });
  }
}

export function __resetVisitorConsultationForTests(): void {
  stopCountdown();
  detachChannel();
  state = initialState;
  listeners.clear();
}
