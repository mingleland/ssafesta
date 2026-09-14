// @vitest-environment jsdom
// 내 부스 관리 — AI 직원 행 미등록 게이트 (S15P21A604-724). QA 발견: 미등록 상태에서 'AI 직원'
// 행을 누르면 빈 등록 폼으로 바로 이동했다. 등록 전에는 팝업으로 등록 페이지 이동을 안내하고,
// 등록돼 있으면 예전처럼 바로 관리 화면으로 이동해야 한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { __resetSessionForTests, setMemberSession } from '../../../auth/model/session';

const getMyBooth = vi.fn();
const getBooth = vi.fn();
const getAiAgent = vi.fn();
const navigate = vi.fn();

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getMyBooth: () => getMyBooth() },
}));
vi.mock('../../../../entities/booth/facadeApi.select', () => ({
  facadeApi: { getBooth: (...args: unknown[]) => getBooth(...args) },
}));
vi.mock('../../../../entities/aiAgent/api', async () => {
  const actual = await vi.importActual<typeof import('../../../../entities/aiAgent/api')>(
    '../../../../entities/aiAgent/api',
  );
  return { ...actual, getAiAgent: (...args: unknown[]) => getAiAgent(...args) };
});
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

const future = () => new Date(Date.now() + 60_000).toISOString();
const myBooth = {
  boothId: 42,
  name: '내 부스',
  lease: { slotCode: 'F11-R06', endsAt: future() },
};

beforeEach(() => {
  getMyBooth.mockReset().mockResolvedValue(myBooth);
  getBooth.mockReset().mockResolvedValue({ facade: null });
  getAiAgent.mockReset();
  navigate.mockReset();
  setMemberSession('member-token', future());
});
afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

async function renderOverlay() {
  const { BoothManagementOverlay } = await import('../../ui/BoothManagementOverlay');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <BoothManagementOverlay onClose={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, client };
}

describe('AI 직원 행 — 미등록 게이트 (-724)', () => {
  // aiAgentQuery 가 아직 로딩 중이면(undefined) 원래대로 이동시키는 게 설계 의도다. 클릭이
  // 그 경합에 걸리지 않도록, 쿼리가 실제로 settle 될 때까지 캐시 상태로 직접 기다린다 — 재시도
  // 클릭은 쓰지 않는다. 재시도 클릭은 로딩 구간에서 navigate 를 먼저 불러버려 그 자체를 검증하지
  // 못하게 만든다.
  async function openGate(client: QueryClient) {
    await screen.findByText('AI 직원');
    await waitFor(() => expect(client.getQueryState(['ai-agent', 42])?.status).toBe('success'));
    fireEvent.click(screen.getByText('AI 직원'));
    expect(await screen.findByText('AI 직원 등록이 필요합니다.')).toBeTruthy();
  }

  it('미등록이면 행을 눌러도 이동하지 않고 팝업을 띄운다', async () => {
    getAiAgent.mockResolvedValue(null);
    const { client } = await renderOverlay();

    await openGate(client);

    expect(navigate).not.toHaveBeenCalled();
  });

  it('팝업의 등록 버튼은 기존 등록 페이지로 보낸다', async () => {
    getAiAgent.mockResolvedValue(null);
    const { client } = await renderOverlay();

    await openGate(client);
    fireEvent.click(screen.getByRole('button', { name: 'AI 직원 등록하러 가기' }));

    expect(navigate).toHaveBeenCalledWith('/app/booths/42/ai-agent');
  });

  it('등록돼 있으면 팝업 없이 바로 이동한다', async () => {
    getAiAgent.mockResolvedValue({ agentId: 1, boothId: 42 });
    await renderOverlay();

    fireEvent.click(await screen.findByText('AI 직원'));

    expect(navigate).toHaveBeenCalledWith('/app/booths/42/ai-agent');
    expect(screen.queryByText('AI 직원 등록이 필요합니다.')).toBeNull();
  });
});
