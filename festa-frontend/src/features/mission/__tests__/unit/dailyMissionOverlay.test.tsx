// @vitest-environment jsdom
// 일일 미션 패널 (S15P21A604-859, GitLab #234 · 계약 #233).
//
// 보상액·상한을 단언하지 않는다 — 응답 값을 그대로 그리는지만 본다. 계약이 10 코인에서 15 코인으로
// 한 번 바뀌었고, FE 에 숫자를 박으면 그때 화면만 옛 값을 말한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ApiError } from '../../../../shared/api/client';
import type { DailyMissionBoard } from '../../../../entities/mission/types';

const getDailyMissions = vi.fn<() => Promise<DailyMissionBoard>>();
const claimDailyMission = vi.fn<(id: string) => Promise<unknown>>();
const toasts = vi.hoisted(() => ({ show: vi.fn() }));
vi.mock('../../../../entities/mission/api.select', () => ({
  missionApi: {
    getDailyMissions: () => getDailyMissions(),
    claimDailyMission: (id: string) => claimDailyMission(id),
  },
}));
vi.mock('../../../../shared/ui/toast/toastStore', () => ({ showToast: toasts.show }));

const { DailyMissionOverlay } = await import('../../ui/DailyMissionOverlay');

function board(overrides: Partial<DailyMissionBoard> = {}): DailyMissionBoard {
  return {
    date: '2026-09-18',
    resetAt: '2026-09-19T00:00:00+09:00',
    dailyCap: 135,
    earnedToday: 30,
    missions: [
      { missionId: 'WORLD_ENTER', reward: 15, progress: 1, goal: 1, status: 'CLAIMABLE' },
      { missionId: 'BOOTH_VISIT_3', reward: 15, progress: 2, goal: 3, status: 'LOCKED' },
      { missionId: 'SLOT_PLAY_3', reward: 15, progress: 3, goal: 3, status: 'CLAIMED' },
    ],
    ...overrides,
  };
}

function apiError(code: string, status: number): ApiError {
  return { code, message: code, status, errors: [], warnings: [] };
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <DailyMissionOverlay onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  getDailyMissions.mockReset();
  claimDailyMission.mockReset();
  toasts.show.mockReset();
  getDailyMissions.mockResolvedValue(board());
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('미션 목록', () => {
  it('서버가 준 미션만 그린다 — 아홉 개를 FE 가 채워 넣지 않는다', async () => {
    renderPanel();
    await screen.findByText('오늘 월드 접속하기');

    expect(screen.getByText('부스 3곳 둘러보기')).toBeTruthy();
    expect(screen.queryByText('슬롯머신 당첨되기')).toBeNull(); // 응답 배열에 없다
  });

  it('상단 획득량과 상한은 응답 값을 그대로 쓴다', async () => {
    getDailyMissions.mockResolvedValue(board({ earnedToday: 45, dailyCap: 120 }));
    renderPanel();
    expect(await screen.findByText('오늘 45 / 120')).toBeTruthy();
  });

  it('진행도는 progress/goal 로 보여 준다', async () => {
    renderPanel();
    await screen.findByText('부스 3곳 둘러보기');

    expect(screen.getByText('2/3')).toBeTruthy();
  });

  it('버튼은 CLAIMABLE 만 활성이고 나머지는 보상액·완료됨 표시다', async () => {
    renderPanel();
    const claimable = await screen.findByRole('button', { name: '받기' });

    expect(claimable.hasAttribute('disabled')).toBe(false);
    expect(screen.getByRole('button', { name: '완료됨' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('button', { name: '15' }).hasAttribute('disabled')).toBe(true);
  });
});

describe('수령', () => {
  it('그 미션 식별자로 한 번 부르고, 성공하면 목록과 잔액을 다시 읽는다', async () => {
    claimDailyMission.mockResolvedValue({ missionId: 'WORLD_ENTER', reward: 15, balanceAfter: 115, claimedAt: 'x' });
    renderPanel();
    fireEvent.click(await screen.findByRole('button', { name: '받기' }));

    await waitFor(() => expect(getDailyMissions).toHaveBeenCalledTimes(2));
    expect(claimDailyMission).toHaveBeenCalledTimes(1);
    expect(claimDailyMission).toHaveBeenCalledWith('WORLD_ENTER');
    expect(toasts.show).toHaveBeenCalledWith('일일 미션 보상으로 15 코인을 받았습니다.', 'success');
  });

  it('ALREADY_CLAIMED 는 배너 없이 재조회로 맞춘다 — 화면이 낡은 것이고 사용자가 잘못한 게 없다', async () => {
    claimDailyMission.mockRejectedValue(apiError('ALREADY_CLAIMED', 409));
    renderPanel();
    fireEvent.click(await screen.findByRole('button', { name: '받기' }));

    await waitFor(() => expect(getDailyMissions).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('NOT_COMPLETED 도 같은 처리다', async () => {
    claimDailyMission.mockRejectedValue(apiError('NOT_COMPLETED', 400));
    renderPanel();
    fireEvent.click(await screen.findByRole('button', { name: '받기' }));

    await waitFor(() => expect(getDailyMissions).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('DAILY_CAP_REACHED 는 문장으로 알린다 — 재조회해도 상태가 그대로라 침묵하면 원인을 알 수 없다', async () => {
    claimDailyMission.mockRejectedValue(apiError('DAILY_CAP_REACHED', 409));
    renderPanel();
    fireEvent.click(await screen.findByRole('button', { name: '받기' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('오늘 받을 수 있는 코인을 모두 받았습니다');
  });

  it('MEMBER_ONLY 는 회원 안내로 갈린다', async () => {
    claimDailyMission.mockRejectedValue(apiError('MEMBER_ONLY', 403));
    renderPanel();
    fireEvent.click(await screen.findByRole('button', { name: '받기' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('회원만');
  });

  it('조회가 실패하면 다시 시도를 준다', async () => {
    getDailyMissions.mockRejectedValue(new Error('boom'));
    renderPanel();

    expect(await screen.findByText('미션을 불러오지 못했습니다')).toBeTruthy();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
  });
});
