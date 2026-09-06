// @vitest-environment jsdom
// 게스트 관리 데스크 (S15P21A604-458, GitLab #139) — 게스트는 부스를 빌릴 수 없다.
//
// 예전 동작: 게스트가 F 를 누르면 GET /booths/mine 이 403 MEMBER_ONLY 로 거절되는데 FE 가 그것을
// 재시도했고, 스피너만 돌다 끝내 "잠시 후 다시 시도해 주세요" + 재시도 버튼이 떴다. 확정 거절에
// 재요청을 유도한 것이다. 여기서 잠그는 것은 셋이다 — 요청을 만들지 않는다 · 사실을 말한다 ·
// 재시도를 권하지 않는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import {
  __resetSessionForTests,
  setGuestSession,
  setMemberSession,
} from '../../../auth/model/session';

const getMyBooth = vi.fn();
vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getMyBooth: () => getMyBooth() },
}));
vi.mock('../../../../entities/booth/facadeApi.select', () => ({
  facadeApi: { getBooth: vi.fn() },
}));

const future = () => new Date(Date.now() + 60_000).toISOString();

beforeEach(() => {
  getMyBooth.mockReset();
  getMyBooth.mockResolvedValue(null);
  __resetSessionForTests();
});
afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

async function renderOverlay() {
  const { BoothManagementOverlay } = await import('../../ui/BoothManagementOverlay');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <BoothManagementOverlay onClose={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('게스트 관리 데스크 (-458)', () => {
  it('요청 자체를 만들지 않는다 — 확정 거절을 서버에 묻지 않는다', async () => {
    setGuestSession('guest-token', future());
    await renderOverlay();
    expect(getMyBooth).not.toHaveBeenCalled();
  });

  it('오류가 아니라 사실을 말한다', async () => {
    setGuestSession('guest-token', future());
    await renderOverlay();
    expect(screen.getByText('로그인하면 부스를 빌릴 수 있어요')).toBeTruthy();
  });

  it('재시도를 권하지 않는다 — 다시 눌러도 답이 같다', async () => {
    setGuestSession('guest-token', future());
    await renderOverlay();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(screen.queryByText('잠시 후 다시 시도해 주세요.')).toBeNull();
  });

  it('회원에게는 예전처럼 조회한다 — 가드가 회원까지 막지 않는다', async () => {
    setMemberSession('member-token', future());
    await renderOverlay();
    expect(getMyBooth).toHaveBeenCalled();
  });
});
