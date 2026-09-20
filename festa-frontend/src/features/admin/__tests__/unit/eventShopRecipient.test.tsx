// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
vi.mock('../../../../entities/admin/api.select', async () => {
  const mock = await import('../../../../entities/admin/api.mock');
  return { adminApi: mock.adminApi };
});
const { AdminOverlay } = await import('../../ui/AdminOverlay');
const { __resetAdminMockForTests } = await import('../../../../entities/admin/api.mock');
const { __resetAdminConsoleForTests } = await import('../../model/consoleState');
const { __resetToastsForTests } = await import('../../../../shared/ui/toast/toastStore');
const { __resetSessionForTests, markBootstrapped, setMemberSession } = await import('../../../auth/model/session');
function renderOverlay() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AdminOverlay onClose={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  __resetAdminMockForTests();
  __resetAdminConsoleForTests();
  __resetToastsForTests();
  __resetSessionForTests();
  setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
describe('구매 표 받는 자 3열', () => {
  it('값 있는 행은 캠퍼스·조·이름을 보여준다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /이벤트 상점/ }));
    const row = (await screen.findByText('황덕')).closest('tr')!;
    expect(within(row).getByText('서울')).not.toBeNull();
    expect(within(row).getByText('A604')).not.toBeNull();
  });
  it('#239 이전 행(null)은 - 로 렌더되고 표가 깨지지 않는다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /이벤트 상점/ }));
    const row = (await screen.findByText('페스타참가자')).closest('tr')!;
    expect(within(row).getAllByText('-').length).toBeGreaterThanOrEqual(3);
  });
});
