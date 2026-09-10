// 미니게임(타이밍 스톱) 서버 계약 — spec 014, `specs/014-minigame/contracts/minigame-api.yaml` 이 정본이다.
//
// **FE 가 만드는 값은 `stoppedSeconds` 하나다.** 판정·오차·티어·보상·일일 한도는 전부 서버가 계산하고
// FE 는 받은 값을 그대로 화면에 쓴다. 클라이언트가 오차를 다시 계산하면 서버 판정과 갈리는 순간
// 사용자에게 두 개의 진실이 생긴다(#134 §2).
//
// 출처: S15P21A604-601 (GitLab #166)
import { api } from '../../shared/api/client';

/** 내장 미니게임 식별자. 지금은 하나뿐이고, 늘어나면 FE 화면 선택이 이 값으로 갈린다 */
export const TIMER_STOP = 'TIMER_STOP';

/**
 * 세션 발급 응답. `serverStartedAt` 이 서버 계측의 기준점이고 **경과 시간 검증도 이 값 기준**이라,
 * 발급해 두고 사용자를 기다리게 하면 그만큼 서버 경과와 클라이언트 측정이 벌어진다(model/timerStop.ts).
 */
export interface TimerStopSession {
  sessionId: string;
  targetSeconds: number;
  failAfterSeconds: number;
  serverStartedAt: string;
}

/**
 * 판정 결과. **모든 필드가 서버 소유다.**
 *
 * `accepted:false`(검증 실패) · `timedOut:true`(실패 종료) · `dailyLimitReached` · 재제출이 전부
 * HTTP 200 이다 — 오류가 아니라 결과의 종류다.
 */
export interface TimerStopResult {
  accepted: boolean;
  errorSeconds: number;
  tier: number;
  timedOut: boolean;
  rewardedCoins: number;
  dailyLimitReached: boolean;
  dailyRemainingCoins: number;
  message: string;
}

const BASE = '/api/v1/minigames/timer-stop';

/**
 * 한 판을 시작한다.
 *
 * 요청 본문을 보내지 않는다 — 서버가 읽지 않는다(계약 §세션 발급). 배포된 Unity 클라이언트가
 * `{gameId}` 를 보내고 있어 서버가 받아만 두고 무시하는 것이고, 새로 만드는 FE 가 그것을 따라 할
 * 이유는 없다.
 *
 * 게스트는 `403 MEMBER_ONLY` 다 — **호출 전에 막는 것이 계약의 의도**다("플레이시킨 뒤 보상이 없다고
 * 알리는 것보다 낫다"). 그 판정은 화면이 한다.
 */
export function startTimerStopSession(): Promise<TimerStopSession> {
  return api<TimerStopSession>(`${BASE}/sessions`, { method: 'POST' });
}

/**
 * 정지 시각을 보고하고 판정을 받는다.
 *
 * 같은 `sessionId` 로 다시 부르면 서버가 **첫 판정을 그대로** 돌려준다(replay). 그래서 네트워크
 * 실패 뒤 같은 값으로 재전송하는 것이 안전하다 — 두 번 지급되지 않는다.
 */
export function submitTimerStopResult(sessionId: string, stoppedSeconds: number): Promise<TimerStopResult> {
  return api<TimerStopResult>(`${BASE}/sessions/${sessionId}/result`, {
    method: 'POST',
    body: JSON.stringify({ stoppedSeconds }),
  });
}
