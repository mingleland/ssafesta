// @vitest-environment jsdom
// 관리자 콘솔 전체 흐름을 mock adapter 위에서 돈다 — BE 도착 전에 화면·상태·조치가 이어지는지 여기서 본다.
// 콘솔은 월드 위 오버레이라 라우팅이 아니라 module store(consoleState)로 섹션을 옮긴다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../../../entities/admin/api.select', async () => {
  const mock = await import('../../../../entities/admin/api.mock');
  return { adminApi: mock.adminApi };
});

const { AdminOverlay } = await import('../../ui/AdminOverlay');
const { __resetAdminMockForTests, __setMockCapability } = await import('../../../../entities/admin/api.mock');
const { __resetAdminConsoleForTests, openAdminSection, selectAdminUser } = await import('../../model/consoleState');
const { __resetToastsForTests, getToastsSnapshot } = await import('../../../../shared/ui/toast/toastStore');
const { __resetSessionForTests, markBootstrapped, setMemberSession } = await import('../../../auth/model/session');

const onClose = vi.fn();

function renderOverlay() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AdminOverlay onClose={onClose} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  __resetAdminMockForTests();
  __resetAdminConsoleForTests();
  __resetToastsForTests();
  // 콘솔은 회원 세션을 전제로 한다 — 권한 질의가 회원일 때만 나간다(model/capability)
  __resetSessionForTests();
  setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
  onClose.mockReset();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const lastToast = () => getToastsSnapshot().at(-1)?.message ?? '';

describe('오버레이 껍데기', () => {
  it('월드 위 모달로 뜨고 운영 현황부터 보여 준다', async () => {
    renderOverlay();
    const dialog = await screen.findByRole('dialog', { name: '관리자 콘솔' });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    await within(dialog).findByText('지금 처리할 것');
  });

  it('닫으면 섹션·선택을 비운다 — 다시 열 때 옛 상태가 방금 고른 것처럼 보이지 않게', async () => {
    renderOverlay();
    await screen.findByText('지금 처리할 것');
    openAdminSection('wallets');
    selectAdminUser(3);
    fireEvent.click(screen.getAllByRole('button', { name: '닫기' })[0]);
    expect(onClose).toHaveBeenCalledTimes(1);

    cleanup();
    renderOverlay();
    await screen.findByText('지금 처리할 것');
  });

  it('관리자가 아니면 콘솔을 그리지 않는다 — 권한이 중간에 회수될 수 있다', async () => {
    __setMockCapability({ admin: false, master: false });
    renderOverlay();
    await screen.findByText('관리자만 이용할 수 있습니다');
    expect(screen.queryByLabelText('콘솔 섹션')).toBeNull();
  });
});

describe('관리자 관리', () => {
  it('마스터 행은 남아 있고 강등 버튼은 disabled 가 아니라 이유를 말한다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /관리자 관리/ }));
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
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /관리자 관리/ }));
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
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /회원 관리/ }));
    fireEvent.change(await screen.findByLabelText('회원 검색'), { target: { value: '참가' } });
    fireEvent.click(screen.getByRole('button', { name: '검색' }));
    fireEvent.click(await screen.findByText('페스타참가자'));

    const detail = await screen.findByLabelText('회원 상세');
    await within(detail).findByText('200 코인');
    fireEvent.click(within(detail).getByRole('button', { name: '계정 정지' }));

    // 사유 없이 확인 → 아무 일도 없다
    fireEvent.click(screen.getByRole('button', { name: '정지' }));
    fireEvent.change(screen.getByLabelText(/정지 사유/), { target: { value: '신고 접수' } });
    fireEvent.click(screen.getByRole('button', { name: '정지' }));

    await waitFor(() => expect(lastToast()).toMatch(/정지했습니다/));
    await waitFor(() => expect(within(detail).getByRole('button', { name: '정지 해제' })).not.toBeNull());
    await within(detail).findByText('신고 접수');
  });

  it('검색 결과가 없으면 빈 상태를 말한다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /회원 관리/ }));
    fireEvent.change(await screen.findByLabelText('회원 검색'), { target: { value: '없는사람' } });
    fireEvent.click(screen.getByRole('button', { name: '검색' }));
    await screen.findByText('검색 결과가 없습니다');
  });

  it('회원에서 고른 사람이 지갑 섹션에 그대로 이어진다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /회원 관리/ }));
    fireEvent.click(await screen.findByText('싸피생'));
    await screen.findByLabelText('회원 상세');

    fireEvent.click(screen.getByRole('button', { name: /코인 · 지갑/ }));
    const wallet = await screen.findByLabelText('지갑 상세');
    await within(wallet).findByText('505 코인');
  });
});

describe('코인 조정 멱등키', () => {
  it('실패 후 재시도는 같은 키를 쓰고, 성공 뒤 새 조정만 새 키다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /코인 · 지갑/ }));
    selectAdminUser(3);
    await screen.findByText('200 코인');
    fireEvent.click(screen.getByRole('button', { name: '코인 조정 시작' }));
    const form = screen.getByLabelText('코인 조정');
    const keyBefore = within(form).getByText(/^[0-9a-f-]{36}$/i).textContent;

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
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /코인 · 지갑/ }));
    selectAdminUser(1);
    const btn = await screen.findByRole('button', { name: '코인 조정 시작' });
    await waitFor(() => expect(btn.getAttribute('aria-disabled')).toBe('true'));
    fireEvent.click(btn);
    expect(screen.getByRole('note').textContent).toMatch(/마스터 계정/);
  });
});

describe('부스·상점·설문', () => {
  it('강제 비공개는 확인 단계와 사유를 거쳐 게시 상태를 내린다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /부스 관리/ }));
    const row = (await screen.findByText('싸피 프로젝트관')).closest('tr')!;
    fireEvent.click(within(row).getByRole('button', { name: '강제 비공개' }));
    fireEvent.change(screen.getByLabelText(/비공개 사유/), { target: { value: '신고 접수' } });
    fireEvent.click(screen.getByRole('button', { name: '비공개' }));
    await waitFor(() => expect(lastToast()).toMatch(/비공개했습니다/));
    // 강제 비공개는 임대까지 회수하므로(S15P21A604-927) 그 자리가 목록에서 사라진다
    await waitFor(() => expect(screen.queryByText('싸피 프로젝트관')).toBeNull());
  });

  it('부스 행에서 운영 관리를 열면 콘솔 안에 머문다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /부스 관리/ }));
    const row = (await screen.findByText('싸피 프로젝트관')).closest('tr')!;
    fireEvent.click(within(row).getByRole('button', { name: '프로젝트' }));
    // 본문은 오버레이를 새로 띄우지 않고 제목줄만 얹는다. 콘솔 내비는 그대로 남는다.
    await screen.findByRole('heading', { name: '프로젝트 관리' });
    expect(screen.getByRole('button', { name: /부스 관리/ })).not.toBeNull();
  });

  it('응모 필터를 당첨만으로 바꾸면 낙첨 행이 빠진다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /이벤트 상점/ }));
    await screen.findByText('응모자');
    fireEvent.click(screen.getByLabelText('응모 결과 필터'));
    fireEvent.click(await screen.findByRole('option', { name: '당첨만' }));
    // 두 단정을 한 waitFor 로 묶는다 — 갈아끼는 동안 표가 잠깐 Loading 으로 비어 있어서,
    // 낙첨이 사라진 순간에 바로 당첨 행을 찾으면 아직 안 돌아와 있다.
    await waitFor(() => {
      expect(screen.queryByText('응모자')).toBeNull();
      expect(screen.queryByText('부스주인')).not.toBeNull();
    });
  });

  it('구매 처리 상태를 바꾸면 표가 따라온다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /이벤트 상점/ }));
    const row = (await screen.findByText('이벤트참여자')).closest('tr')!;
    expect(within(row).getByText('지급 대기')).not.toBeNull();
    fireEvent.click(within(row).getByRole('button', { name: '처리' }));
    fireEvent.change(screen.getByLabelText(/다음 상태/), { target: { value: 'FULFILLED' } });
    fireEvent.click(screen.getByRole('button', { name: '상태 변경' }));
    await waitFor(() => expect(lastToast()).toMatch(/지급 완료/));
    await waitFor(() => expect(within(row).queryByText('지급 완료')).not.toBeNull());
  });

  it('설문은 현황·집계·참여자·개별 응답으로 이어진다', async () => {
    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: /이벤트 설문/ }));
    await screen.findByText('SSAFESTA 2026 축제 만족도 설문');
    expect(screen.getByText('총 참여자').previousSibling?.textContent).toBe('4');
    await screen.findByText(/가장 좋았던 공간은/);
    fireEvent.click(await screen.findByText('부스주인'));
    const detail = await screen.findByLabelText('개별 응답');
    await within(detail).findByText('부스 배치 범위를 넓혀 주세요');
  });
});
