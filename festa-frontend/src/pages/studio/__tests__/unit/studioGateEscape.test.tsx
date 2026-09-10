// @vitest-environment jsdom
// Gate 화면은 Shell 없이 카드만 그린다 — 툴바의 '뒤로'가 없고 ESC Game Menu 는 World 전용이라
// 여기서 열리지 않는다. 그래서 링크 없는 Gate 는 주소창 말고 나갈 길이 없는 막다른 화면이 된다.
// 세션 만료 → returnTo 가 이 화면으로 되돌리면 사용자가 그대로 갇힌다(감사 항목 10).
//
// 여기서 묻는 것은 "오류 문구가 떴다"가 아니라 **"나갈 수 있다"** 다. 문구는 바뀌어도 되지만
// 탈출구가 사라지면 같은 결함이 다시 난다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

const BOOTH_ID = 7;

const getMyBooth = vi.fn();
const getDraft = vi.fn();

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getMyBooth: () => getMyBooth(), getSlots: () => Promise.resolve([]) },
}));

vi.mock('../../../../entities/layout/api.select', () => ({
  layoutApi: {
    getDraft: (boothId: number) => getDraft(boothId),
    getTemplates: () => Promise.resolve({ templates: [] }),
  },
}));

const { StudioPage } = await import('../../StudioPage');
const { __resetSessionForTests, markBootstrapped, setMemberSession } = await import(
  '../../../../features/auth/model/session'
);

function renderStudio() {
  // retry: false — 실패 케이스를 재시도로 늘어뜨리면 테스트가 타이머에 묶인다
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      {/* 경로 파라미터가 있어야 boothId 가 잡힌다 — 없으면 NaN 이라 항상 not-owner 로 떨어진다 */}
      <MemoryRouter initialEntries={[`/app/studio/${BOOTH_ID}`]}>
        <Routes>
          <Route path="/app/studio/:boothId" element={<StudioPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Gate 카드 안의 탈출 링크 — 목적지는 바뀔 수 있으니 "앱 내부 경로로 나가는 링크"만 본다 */
async function expectEscapeLink() {
  await waitFor(() => {
    const links = screen.getAllByRole('link');
    expect(links.length).toBeGreaterThan(0);
    expect(links.some((a) => (a.getAttribute('href') ?? '').startsWith('/app'))).toBe(true);
  });
}

beforeEach(() => {
  __resetSessionForTests();
  setMemberSession('token', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
  getMyBooth.mockReset();
  getDraft.mockReset();
});

afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

describe('Booth Studio Gate — 막다른 화면을 만들지 않는다', () => {
  it('소유 확인이 실패해도 나갈 수 있고 다시 시도할 수 있다', async () => {
    getMyBooth.mockRejectedValue(new Error('network'));
    getDraft.mockResolvedValue(null);

    renderStudio();

    await screen.findByText('부스 소유 정보를 불러오지 못했습니다.');
    await expectEscapeLink();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
  });

  it('작업본을 불러오지 못해도 나갈 수 있고 다시 시도할 수 있다', async () => {
    getMyBooth.mockResolvedValue({ boothId: BOOTH_ID, name: '내 부스', status: 'ACTIVE', lease: null });
    getDraft.mockRejectedValue(new Error('network'));

    renderStudio();

    await screen.findByText('작업본을 불러오지 못했습니다.');
    await expectEscapeLink();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
  });

  it('임대가 만료되면 나갈 수 있다 — 되물어도 답이 같으므로 재시도는 주지 않는다', async () => {
    getMyBooth.mockResolvedValue({ boothId: BOOTH_ID, name: '내 부스', status: 'ACTIVE', lease: null });
    getDraft.mockRejectedValue({
      code: 'BOOTH_LEASE_EXPIRED',
      message: '임대가 만료되었습니다.',
      requestId: 'test',
      errors: [],
      warnings: [],
    });

    renderStudio();

    await screen.findByText('임대가 만료되어 이 부스를 편집할 수 없습니다.');
    await expectEscapeLink();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
  });
});
