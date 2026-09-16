// @vitest-environment jsdom
// 관리자 콘솔 전체 흐름을 mock adapter 위에서 돈다 — BE 도착 전에 화면·상태·조치가 이어지는지 여기서 본다.
// 각 섹션의 "목록 → 선택 → 상세 → 조치" 와 마스터 보호·멱등키·확인 단계를 잠근다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../../../entities/admin/api.select', async () => {
  const mock = await import('../../../../entities/admin/api.mock');
  return { adminApi: mock.adminApi };
});
vi.mock('../../../../features/shell/ui/PageShell', () => ({
  PageShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const { AdminPage } = await import('../../AdminPage');
const { __resetAdminMockForTests } = await import('../../../../entities/admin/api.mock');
const { __resetToastsForTests, getToastsSnapshot } = await import('../../../../shared/ui/toast/toastStore');

function renderConsole(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/app/admin/:section" element={<AdminPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => { __resetAdminMockForTests(); __resetToastsForTests(); vi.spyOn(console, 'warn').mockImplementation(() => {}); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const lastToast = () => getToastsSnapshot().at(-1)?.message ?? '';

describe('관리자 관리', () => {
  it('마스터 행은 남아 있고 강등 버튼은 disabled 가 아니라 이유를 말한다', async () => {
    renderConsole('/app/admin/admins');
    const list = await screen.findByLabelText('관리자 목록');
    await within(list).findByText('구글 황덕');
    const masterRow = within(list).getByText('구글 황덕').closest('tr')!;
    const btn = within(masterRow).getByRole('button', { name: '강등' });
    expect(btn.getAttribute('aria-disabled')).toBe('true');
    expect(btn.hasAttribute('disabled')).toBe(false);
    fireEvent.click(btn);
    expect(within(masterRow).getByRole('note').textContent).toMatch(/마스터 계정은 변경할 수 없습니다/);
  });

  it('승격은 목록을 갱신하고, 중복 승격은 운영 안내로 막힌다', async () => {
    renderConsole('/app/admin/admins');
    await screen.findByText('구글 황덕');
    fireEvent.change(screen.getByLabelText('회원 번호'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: '승격' }));
    await screen.findByText('페스타참가자');
    expect(lastToast()).toMatch(/승격했습니다/);

    fireEvent.change(screen.getByLabelText('회원 번호'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: '승격' }));
    await screen.findByText('이미 관리자입니다');
  });
});

describe('회원 관리', () => {
  it('검색 → 선택 → 상세 → 정지(사유 필수) → 이력이 같은 상태를 말한다', async () => {
    renderConsole('/app/admin/members');
    fireEvent.change(await screen.findByLabelText('회원 검색'), { target: { value: '참가' } });
    fireEvent.click(screen.getByRole('button', { name: '검색' }));
    fireEvent.click(await screen.findByText('페스타참가자'));

    const detail = await screen.findByLabelText('회원 상세');
    await within(detail).findByText('200 코인');
    fireEvent.click(within(detail).getByRole('button', { name: '계정 정지' }));

    // 사유 없이 확인 → 아무 일도 없다
    fireEvent.click(screen.getByRole('button', { name: '정지' }));
    expect(within(detail).queryByText('정지 사유는')).toBeNull();
    fireEvent.change(screen.getByLabelText(/정지 사유/), { target: { value: '신고 접수' } });
    fireEvent.click(screen.getByRole('button', { name: '정지' }));

    await waitFor(() => expect(lastToast()).toMatch(/정지했습니다/));
    await waitFor(() => expect(within(detail).getByRole('button', { name: '정지 해제' })).not.toBeNull());
    await within(detail).findByText('신고 접수');
  });

  it('검색 결과가 없으면 빈 상태를 말한다', async () => {
    renderConsole('/app/admin/members');
    fireEvent.change(await screen.findByLabelText('회원 검색'), { target: { value: '없는사람' } });
    fireEvent.click(screen.getByRole('button', { name: '검색' }));
    await screen.findByText('검색 결과가 없습니다');
  });
});

describe('코인 조정 멱등키', () => {
  it('실패 후 재시도는 같은 키를 쓰고, 성공 뒤 새 조정만 새 키다', async () => {
    renderConsole('/app/admin/wallets?userId=3');
    await screen.findByText('200 코인');
    fireEvent.click(screen.getByRole('button', { name: '코인 조정 시작' }));
    const form = screen.getByLabelText('코인 조정');
    const keyBefore = within(form).getByText(/^[0-9a-f-]{36}$/i).textContent;

    // 잔액 초과 회수 → 실패. 키는 그대로
    fireEvent.change(within(form).getByLabelText(/금액/), { target: { value: '-999' } });
    fireEvent.click(within(form).getByRole('button', { name: '조정 실행' }));
    await within(form).findByText('잔액보다 많이 회수할 수 없습니다');
    expect(within(form).getByText(/^[0-9a-f-]{36}$/i).textContent).toBe(keyBefore);

    fireEvent.change(within(form).getByLabelText(/금액/), { target: { value: '50' } });
    fireEvent.click(within(form).getByRole('button', { name: '같은 요청 다시 시도' }));
    await within(form).findByText(/반영됐습니다/);
    await screen.findByText('250 코인');

    fireEvent.click(within(form).getByRole('button', { name: '새 조정 시작' }));
    const nextForm = screen.getByLabelText('코인 조정');
    expect(within(nextForm).getByText(/^[0-9a-f-]{36}$/i).textContent).not.toBe(keyBefore);
  });

  it('마스터에게는 조정 시작 자체가 잠기고 이유가 보인다', async () => {
    renderConsole('/app/admin/wallets?userId=1');
    const btn = await screen.findByRole('button', { name: '코인 조정 시작' });
    await waitFor(() => expect(btn.getAttribute('aria-disabled')).toBe('true'));
    fireEvent.click(btn);
    expect(screen.getByRole('note').textContent).toMatch(/마스터 계정/);
  });
});

describe('부스·상점·설문', () => {
  it('강제 비공개는 확인 단계와 사유를 거쳐 게시 상태를 내린다', async () => {
    renderConsole('/app/admin/booths');
    const row = (await screen.findByText('싸피 프로젝트관')).closest('tr')!;
    fireEvent.click(within(row).getByRole('button', { name: '강제 비공개' }));
    fireEvent.change(screen.getByLabelText(/비공개 사유/), { target: { value: '신고 접수' } });
    fireEvent.click(screen.getByRole('button', { name: '비공개' }));
    await waitFor(() => expect(lastToast()).toMatch(/비공개했습니다/));
    await waitFor(() => expect(within(row).queryByText('미게시')).not.toBeNull());
  });

  it('구매 처리 상태를 바꾸면 표가 따라온다', async () => {
    renderConsole('/app/admin/shop');
    const row = (await screen.findByText('이벤트참여자')).closest('tr')!;
    expect(within(row).getByText('지급 대기')).not.toBeNull();
    fireEvent.click(within(row).getByRole('button', { name: '처리' }));
    fireEvent.change(screen.getByLabelText(/다음 상태/), { target: { value: 'FULFILLED' } });
    fireEvent.click(screen.getByRole('button', { name: '상태 변경' }));
    await waitFor(() => expect(lastToast()).toMatch(/지급 완료/));
    await waitFor(() => expect(within(row).queryByText('지급 완료')).not.toBeNull());
  });

  it('설문은 현황·집계·참여자·개별 응답으로 이어진다', async () => {
    renderConsole('/app/admin/surveys');
    await screen.findByText('SSAFESTA 2026 축제 만족도 설문');
    expect(screen.getByText('총 참여자').previousSibling?.textContent).toBe('4');
    await screen.findByText(/가장 좋았던 공간은/);
    fireEvent.click(await screen.findByText('부스주인'));
    const detail = await screen.findByLabelText('개별 응답');
    await within(detail).findByText('부스 배치 범위를 넓혀 주세요');
  });
});

