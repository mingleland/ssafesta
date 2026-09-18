// Access Token 을 만료 전에 미리 갱신한다 (S15P21A604-828).
//
// **왜 반응형만으로는 부족한가.** 지금까지 갱신은 401 을 맞아야만 일어났다. React 는 그래도
// 되살아난다 — client.ts 가 401 을 가로채 갱신 후 원요청을 다시 보낸다. 그런데 이 앱에는
// 토큰을 쓰는 소비자가 하나가 아니다. Unity 가 같은 Access Token 으로 REST 를 직접 부르고,
// 4xx 를 최종 실패로 처리한다 — 재시도 경로는 '토큰이 아직 없다' 쪽에만 있다.
//
// 그래서 만료 경계에 월드 부팅이 걸리면 Unity 가 먼저 12슬롯 게시본·world-session·카탈로그를
// 던지고 전부 401 로 죽는다. React 는 그 뒤에 조용히 갱신하고 새 토큰을 주입하지만, Unity 는
// 이미 포기한 뒤라 부스가 기본 프레임으로 남고 간판이 'N번 부스' 가 된다(2026-09-16 실측).
//
// 만료 **전에** 갱신해 두면 그 창이 거의 닫힌다. 완전히 닫히지는 않는다 — 서버가 refresh 마다
// 세션 식별자(sid)를 회전시켜 옛 토큰을 즉시 폐기하므로(MemberSessionService.issue), 재주입이
// Unity 에 닿기까지의 짧은 구간은 남는다. 그 구간까지 없애려면 Unity 가 401 재시도를 갖거나
// BE 가 회전 직후 옛 sid 에 유예를 둬야 한다 — 둘 다 이 파일 밖이다.
import { getSessionSnapshot, subscribeSession } from './session';
import { refreshSessionNow } from './unauthorizedHandler';

/** 만료 몇 밀리초 전에 갱신할지. 왕복과 Unity 재주입까지 감당할 만큼만 앞선다 */
const MARGIN_MS = 2 * 60 * 1000;

/**
 * 이미 지난 만료를 받았을 때의 최소 대기. 0 으로 두면 서버가 과거 시각을 돌려주는 순간
 * 갱신이 제자리에서 도는 고리가 된다 — 실패를 폭주로 바꾸지 않는다.
 */
const MIN_DELAY_MS = 5 * 1000;

let timer: ReturnType<typeof setTimeout> | null = null;
let unsubscribe: (() => void) | null = null;

function clearTimer(): void {
  if (timer !== null) {
    clearTimeout(timer);
    timer = null;
  }
}

function reschedule(): void {
  clearTimer();
  const { kind, expiresAt } = getSessionSnapshot();
  // 게스트는 자동 재발급을 하지 않는다(FR-009a) — 재입장 안내가 제품 동선이다.
  if (kind !== 'member' || expiresAt === null) return;

  const remaining = Date.parse(expiresAt) - Date.now();
  if (Number.isNaN(remaining)) return;
  const delay = Math.max(remaining - MARGIN_MS, MIN_DELAY_MS);

  timer = setTimeout(() => {
    timer = null;
    // 실패해도 여기서 세션을 끊지 않는다. 판정은 401 경로 한 곳이 소유한다 — 두 곳이 각자
    // 끊으면 안내 문구와 시점이 갈린다. 갱신에 실패하면 다음 요청의 401 이 그 길을 탄다.
    void refreshSessionNow();
  }, delay);
}

/** 앱 시작 시 1회. 세션이 바뀔 때마다 다음 갱신 시각을 다시 잡는다 */
export function startRefreshScheduler(): void {
  if (unsubscribe !== null) return;
  unsubscribe = subscribeSession(reschedule);
  reschedule();
}

/** 테스트 전용 — 모듈 스코프 타이머·구독을 테스트 간에 격리한다 */
export function __resetRefreshSchedulerForTests(): void {
  clearTimer();
  unsubscribe?.();
  unsubscribe = null;
}
