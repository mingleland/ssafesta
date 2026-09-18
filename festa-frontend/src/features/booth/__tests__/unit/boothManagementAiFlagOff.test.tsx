// @vitest-environment jsdom
// 내 부스 관리창 — AI 전시 에셋 섹션은 플래그 한 줄로 사라진다 (S15P21A604-817).
// 모듈 mock 은 파일 단위라 boothManagementRedesign.test.tsx 와 분리했다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { __resetSessionForTests, setMemberSession } from '../../../auth/model/session';

vi.mock('../../model/boothManagementFlags', () => ({ SHOW_AI_ASSET_SECTION: false, PUBLISH_AI_AGENT_BINDING: false }));

const future = () => new Date(Date.now() + 60_000).toISOString();
vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: {
    getMyBooth: () => Promise.resolve({ boothId: 42, name: '내 부스', lease: { slotId: 6, slotCode: 'F11-R06', endsAt: future() } }),
  },
}));
vi.mock('../../../../entities/booth/facadeApi.select', () => ({
  facadeApi: { getBooth: () => Promise.resolve({ facade: null }) },
}));
vi.mock('../../../../entities/project/api.select', () => ({
  projectApi: { getMyProjects: () => Promise.resolve({ projects: [] }) },
}));
vi.mock('../../../../entities/aiAgent/api', () => ({ getAiAgent: () => Promise.resolve(null) }));

beforeEach(() => setMemberSession('member-token', future()));
afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

describe('AI 전시 에셋 섹션 플래그 off (-817)', () => {
  it('섹션만 사라지고 나머지는 그대로다', async () => {
    const { BoothManagementOverlay } = await import('../../ui/BoothManagementOverlay');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <BoothManagementOverlay onClose={() => {}} />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByText('부스 운영 관리');
    expect(screen.queryByText('AI 전시 에셋 관리')).toBeNull();
    expect(screen.queryByRole('button', { name: 'AI 에셋 생성' })).toBeNull();
    expect(screen.getByText('부스 미리보기')).toBeTruthy();
    expect(screen.getByRole('button', { name: /^부스 반납하기/ })).toBeTruthy();
  });
});
