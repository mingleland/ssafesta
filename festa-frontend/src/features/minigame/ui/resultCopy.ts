// 결과 문구 — 서버 `message` 를 그대로 내보내지 않는다 (S15P21A604-601, GitLab #166).
//
// 서버 `message` 는 **ASCII 영문 전용**이다. Unity WebGL 의 IMGUI 가 한글을 그리지 못해서 생긴
// 제약이고(T-22, spec 014 T013), React 에는 그 제약이 없다. 값 예: `+5 coins (tier 2)` ·
// `Daily limit reached` · `Too slow`. 그것을 그대로 노출하면 화면만 영어가 된다.
//
// 그래서 **구조 필드로 문구를 만든다.** 문구 원안은 Unity HUD 가 쓰던 것을 게임 파트가 넘겨줬다(#166).
import type { TimerStopResult } from '../../../entities/minigame/api';

export interface ResultCopy {
  /** 한 줄 결론 */
  headline: string;
  /** 부제 — 없으면 렌더하지 않는다 */
  detail?: string;
  tone: 'reward' | 'neutral' | 'fail';
}

/**
 * 우선순위대로 **먼저 잡히는 하나**를 결론으로 쓴다.
 *
 * 예외가 하나 있다 — 보상을 받았는데 그것으로 한도에 도달한 경우다. 서버 `dailyLimitReached` 는
 * "이번 판이 잘렸다" 가 아니라 "지금 더 받을 수 없다" 라서, 누적 45 에서 5 를 **온전히** 받아도 참이다.
 * 둘 다 사실이고 사용자가 알아야 하므로 보상을 본문에, 한도를 부제에 함께 싣는다.
 */
export function describeResult(result: TimerStopResult): ResultCopy {
  if (!result.accepted) {
    return { headline: '결과가 인정되지 않았습니다 — 다시 시도해 주세요', tone: 'fail' };
  }
  if (result.timedOut) {
    return { headline: '실패 — 제한 시간을 넘겼습니다', tone: 'fail' };
  }
  if (result.rewardedCoins > 0) {
    return {
      headline: `+${result.rewardedCoins} 코인`,
      detail: result.dailyLimitReached
        ? '오늘 보상 한도에 도달했습니다 — 게임은 계속할 수 있어요'
        : `오늘 남은 보상 ${result.dailyRemainingCoins} 코인`,
      tone: 'reward',
    };
  }
  if (result.dailyLimitReached) {
    return {
      headline: '오늘 보상 한도에 도달했습니다',
      detail: '게임은 계속할 수 있어요',
      tone: 'neutral',
    };
  }
  return { headline: '아쉬워요 — 보상 구간에 들지 못했습니다', tone: 'neutral' };
}

/** 오차 표시는 **서버 `errorSeconds`** 만 쓴다. 클라이언트 계산값과 갈릴 수 있다(#134 §2) */
export function formatErrorSeconds(errorSeconds: number): string {
  return `${errorSeconds.toFixed(3)}초`;
}
