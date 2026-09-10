// 타이밍 스톱 진행 상태 — 화면과 분리해 전이만 담는다 (S15P21A604-601, GitLab #166).
//
// **왜 READY 대기가 없는가.** 서버는 세션 발급 시각(`serverStartedAt`)부터 제출이 도착한 시각까지의
// 경과와 클라이언트가 보고한 `stoppedSeconds` 를 비교해, 차이가 허용오차(2초, `application.yml`
// `elapsed-tolerance-seconds`)를 넘으면 세션을 `REJECTED` 로 **소진**시킨다. 발급해 두고 사용자가
// 시작 버튼을 기다리는 구간을 두면 그 대기 시간이 그대로 오차가 되어 한 판을 잃는다.
// 그래서 **[시작]을 누른 뒤 발급하고, 201 이 오는 즉시 달린다.** 정지하면 즉시 제출한다.
//
// 같은 이유로 결과 확인 단계를 끼우지 않는다 — "확인" 을 기다리는 동안 세션이 소진된다.
import type { TimerStopResult, TimerStopSession } from '../../../entities/minigame/api';

/**
 * `TIMED_OUT`·`EXPIRED` 를 별도 상태로 두지 않는다. 서버가 `timedOut:true` 로 200 을 주는 것은
 * 정상 플레이이고, 404 도 사용자에게는 결과 화면이다 — 전이가 같고 렌더만 다르므로 `RESULT` 안의
 * variant 로 둔다. 상태를 늘리면 전이표만 커지고 얻는 것이 없다.
 */
export type TimerStopPhase =
  | { kind: 'IDLE' }
  | { kind: 'ISSUING_SESSION' }
  | { kind: 'RUNNING'; session: TimerStopSession; startedAt: number }
  | { kind: 'SUBMITTING'; session: TimerStopSession; stoppedSeconds: number }
  | { kind: 'RESULT'; result: TimerStopResult; stoppedSeconds: number }
  | { kind: 'RESULT_EXPIRED' }
  | { kind: 'ERROR'; reason: TimerStopErrorReason };

/**
 * `MEMBER_ONLY` 는 화면이 발급 **전에** 막으므로 정상 흐름에서는 오지 않는다. 그래도 두는 이유는
 * 세션 만료·다른 탭 로그아웃처럼 화면 판정과 서버 판정이 갈리는 순간이 있기 때문이다.
 *
 * `VALIDATION` 은 우리가 보낸 값이 계약을 어겼다는 뜻이다 — **자동으로 플레이를 재개하지 않는다.**
 * 같은 상태로 다시 달리면 같은 실패를 반복한다.
 */
export type TimerStopErrorReason = 'MEMBER_ONLY' | 'VALIDATION' | 'UNKNOWN';

export const IDLE: TimerStopPhase = { kind: 'IDLE' };

/** 밀리초 단위로 자른다 — 서버가 소수점 3자리로 반올림한다(계약 `SubmitCommand`) */
export function elapsedSeconds(startedAt: number, now: number): number {
  return Math.max(0, Math.round(now - startedAt)) / 1000;
}

/** 제한 시간을 넘겼는가. 판정 자체는 서버가 하고, 이 값은 **자동 정지 시점**을 정하는 데만 쓴다 */
export function isPastFailAfter(phase: TimerStopPhase, now: number): boolean {
  if (phase.kind !== 'RUNNING') return false;
  return elapsedSeconds(phase.startedAt, now) > phase.session.failAfterSeconds;
}

/**
 * 오류 응답 → 화면이 다룰 사유.
 *
 * `404` 는 오류가 아니라 **결과의 한 종류**라 여기서 다루지 않는다 — 호출부가 `RESULT_EXPIRED` 로 간다.
 */
export function toErrorReason(status: number | undefined): TimerStopErrorReason {
  if (status === 401 || status === 403) return 'MEMBER_ONLY';
  if (status === 400) return 'VALIDATION';
  return 'UNKNOWN';
}
