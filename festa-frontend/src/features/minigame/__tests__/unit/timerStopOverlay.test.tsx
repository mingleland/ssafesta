// @vitest-environment jsdom
// 타이밍 스톱 오버레이 (S15P21A604-601, GitLab #166).
//
// 잠그는 것 넷.
//   ① 게스트는 **세션을 발급하지 않는다** — 서버가 403 MEMBER_ONLY 로 발급 단계에서 거부하는 것이
//      계약이고("플레이시킨 뒤 보상이 없다고 알리는 것보다 낫다"), 화면이 그보다 앞에서 막는다
//   ② 발급 성공 뒤 **기다리는 구간이 없다** — 서버가 발급 시각부터 경과를 재므로 대기가 곧 오차다
//   ③ 제출은 판마다 **한 번**이다 — 연타·더블클릭이 두 판을 소비하면 안 된다
//   ④ 화면 오차는 **서버 errorSeconds** 다 — 클라이언트 측정값과 다른 fixture 로 확인한다
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { TimerStopOverlay } from '../../ui/TimerStopOverlay';
import * as minigameApi from '../../../../entities/minigame/api';
import { setGuestSession, setMemberSession, clearSession } from '../../../auth/model/session';
import type { TimerStopResult, TimerStopSession } from '../../../../entities/minigame/api';

/**
 * 오류 봉투 — `errors`·`warnings` 는 비어 있어도 **항상 있다**(OpenAPI `ApiError` required).
 * 이것을 빼면 `isApiError` 가 false 가 되어 status 분기가 통째로 죽는다.
 */
function envelope(code: string, status: number) {
  return { code, message: code, requestId: 'test', errors: [], warnings: [], status };
}

const SESSION: TimerStopSession = {
  sessionId: '3f1a6d2c-8b5e-4c11-9a77-2d0e5f8c4b31',
  targetSeconds: 7.381,
  failAfterSeconds: 10.381,
  serverStartedAt: '2026-09-10T09:00:00Z',
};

function result(over: Partial<TimerStopResult> = {}): TimerStopResult {
  return {
    accepted: true,
    errorSeconds: 0.029,
    tier: 2,
    timedOut: false,
    rewardedCoins: 5,
    dailyLimitReached: false,
    dailyRemainingCoins: 45,
    message: '+5 coins (tier 2)',
    ...over,
  };
}

function renderOverlay() {
  return render(
    <MemoryRouter>
      <TimerStopOverlay />
    </MemoryRouter>,
  );
}

let start: ReturnType<typeof vi.spyOn>;
let submit: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  start = vi.spyOn(minigameApi, 'startTimerStopSession').mockResolvedValue(SESSION);
  submit = vi.spyOn(minigameApi, 'submitTimerStopResult').mockResolvedValue(result());
  setMemberSession('token', new Date(Date.now() + 3_600_000).toISOString());
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  clearSession();
});

describe('게스트 차단 — 발급 전에 막는다', () => {
  it('게스트에게는 시작 진입점이 없고 세션도 발급하지 않는다', () => {
    clearSession();
    setGuestSession('guest-token', new Date(Date.now() + 3_600_000).toISOString());

    renderOverlay();

    expect(screen.getByText('로그인이 필요합니다')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '시작' })).toBeNull();
    expect(start).not.toHaveBeenCalled();
  });
});

describe('세션 발급과 진행', () => {
  it('시작을 눌러야 발급하고, 발급되면 대기 없이 바로 달린다', async () => {
    renderOverlay();
    expect(start).not.toHaveBeenCalled(); // 열자마자 발급하지 않는다

    fireEvent.click(screen.getByRole('button', { name: '시작' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '멈추기' })).toBeTruthy());
    expect(start).toHaveBeenCalledTimes(1);
    // 발급과 RUNNING 사이에 '준비됨' 같은 대기 단계가 없다 — 시작 버튼이 곧바로 멈추기로 바뀐다
    expect(screen.queryByRole('button', { name: '시작' })).toBeNull();
  });

  it('403 은 회원 전용 안내로 끝난다 — 플레이시키지 않는다', async () => {
    start.mockRejectedValue(envelope('MEMBER_ONLY', 403));
    renderOverlay();

    fireEvent.click(screen.getByRole('button', { name: '시작' }));

    await waitFor(() => expect(screen.getByText('보상 게임은 회원만 참여할 수 있어요')).toBeTruthy());
    expect(submit).not.toHaveBeenCalled();
  });

  it('Space로 시작하고 다시 누르면 멈춘다', async () => {
    renderOverlay();

    fireEvent.keyDown(window, { code: 'Space' });
    await waitFor(() => expect(screen.getByRole('button', { name: '멈추기' })).toBeTruthy());
    fireEvent.keyDown(window, { code: 'Space' });

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  });
});

describe('제출', () => {
  async function startAndStop() {
    renderOverlay();
    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    const stop = await screen.findByRole('button', { name: '멈추기' });
    return stop;
  }

  it('연타해도 한 번만 제출한다', async () => {
    const stop = await startAndStop();

    fireEvent.click(stop);
    fireEvent.click(stop);
    fireEvent.click(stop);

    await waitFor(() => expect(screen.getByText('+5 코인')).toBeTruthy());
    expect(submit).toHaveBeenCalledTimes(1);
  });

  it('보낸 본문은 stoppedSeconds 하나다 — 계약에 없는 필드를 만들지 않는다', async () => {
    const stop = await startAndStop();
    fireEvent.click(stop);

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
    const [sessionId, stoppedSeconds] = submit.mock.calls[0] as unknown as [string, number];
    expect(sessionId).toBe(SESSION.sessionId);
    expect(typeof stoppedSeconds).toBe('number');
  });

  it('400 은 자동으로 플레이를 재개하지 않는다', async () => {
    submit.mockRejectedValue(envelope('VALIDATION_FAILED', 400));
    const stop = await startAndStop();
    fireEvent.click(stop);

    await waitFor(() => expect(screen.getByText('결과를 보내지 못했습니다')).toBeTruthy());
    // 스스로 달리기 시작하지 않는다 — 사용자가 다시 하기를 눌러야 한다
    expect(screen.queryByRole('button', { name: '멈추기' })).toBeNull();
    expect(start).toHaveBeenCalledTimes(1);
  });

  it('404 는 오류가 아니라 만료 결과다', async () => {
    submit.mockRejectedValue(envelope('MINIGAME_SESSION_NOT_FOUND', 404));
    const stop = await startAndStop();
    fireEvent.click(stop);

    await waitFor(() => expect(screen.getByText('게임 세션이 만료됐어요')).toBeTruthy());
  });
});

describe('결과 variant — 서버 값을 그대로 쓴다', () => {
  async function play(over: Partial<TimerStopResult>) {
    submit.mockResolvedValue(result(over));
    renderOverlay();
    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    fireEvent.click(await screen.findByRole('button', { name: '멈추기' }));
  }

  it('보상을 받으면 남은 한도를 함께 보여준다', async () => {
    await play({});
    expect(await screen.findByText('+5 코인')).toBeTruthy();
    expect(screen.getByText('오늘 남은 보상 45 코인')).toBeTruthy();
  });

  it('보상을 받고 한도에 도달했으면 둘 다 보여준다 — 하나만 고르지 않는다', async () => {
    await play({ dailyLimitReached: true, dailyRemainingCoins: 0 });
    expect(await screen.findByText('+5 코인')).toBeTruthy();
    expect(screen.getByText('오늘 보상 한도에 도달했습니다 — 게임은 계속할 수 있어요')).toBeTruthy();
  });

  it('accepted=false 가 timedOut 보다 먼저다', async () => {
    await play({ accepted: false, timedOut: true, rewardedCoins: 0 });
    expect(await screen.findByText('결과가 인정되지 않았습니다 — 다시 시도해 주세요')).toBeTruthy();
  });

  it('timedOut 은 실패로 안내한다', async () => {
    await play({ timedOut: true, tier: 0, rewardedCoins: 0 });
    expect(await screen.findByText('실패 — 제한 시간을 넘겼습니다')).toBeTruthy();
  });

  it('보상 구간에 못 들면 그렇게 말한다', async () => {
    await play({ tier: 0, rewardedCoins: 0 });
    expect(await screen.findByText('아쉬워요 — 보상 구간에 들지 못했습니다')).toBeTruthy();
  });

  it('화면 오차는 서버 errorSeconds 다 — 클라이언트 측정값이 아니다', async () => {
    await play({ errorSeconds: 1.234 });
    expect(await screen.findByText('오차 1.234초')).toBeTruthy();
  });

  it('서버 message(영문)를 그대로 노출하지 않는다', async () => {
    await play({ message: 'Daily limit reached' });
    await screen.findByText('+5 코인');
    expect(screen.queryByText('Daily limit reached')).toBeNull();
  });
});

// 목표 초 가시성 (S15P21A604-733). 부제에 있던 값을 플레이 영역으로 올렸다 — 달리는 중에 읽어야
// 하는 값이 제목 옆 작은 글씨에 있으면 눈이 가지 않는다. 값·포맷은 그대로다.
describe('목표 초 가시성 (-733)', () => {
  it('달리는 중에는 목표 초가 플레이 영역의 독립 요소로 보인다', async () => {
    const { container } = renderOverlay();
    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    await waitFor(() => expect(container.querySelector('.ts-target')).not.toBeNull());
    expect(container.querySelector('.ts-target-value')?.textContent).toBe('7.381');
  });

  it('부제는 목표 값을 담지 않는다 — 한 값이 두 곳에 있으면 한쪽이 낡는다', async () => {
    const { container } = renderOverlay();
    fireEvent.click(screen.getByRole('button', { name: '시작' }));
    await waitFor(() => expect(container.querySelector('.ts-target')).not.toBeNull());
    expect(container.querySelector('.festa-overlay-subtitle')?.textContent).toBe('목표 시간에 맞춰 멈추세요');
  });

  it('시작 전에는 목표 요소가 없다 — 아직 정해지지 않은 값이다', () => {
    const { container } = renderOverlay();
    expect(container.querySelector('.ts-target')).toBeNull();
  });
});
