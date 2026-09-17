// @vitest-environment jsdom
// 부스 조기 반납 (S15P21A604-753, GitLab #199).
//
// 지키는 것은 404 규약이다 — `ACTIVE_LEASE_NOT_FOUND` 는 실패가 아니라 "화면이 낡았다" 는
// 신호라 배너를 만들지 않고 재조회만 한다. 재시도 DELETE 도 같은 자리로 온다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { __resetSessionForTests, setMemberSession } from '../../../auth/model/session';

const getMyBooth = vi.fn();
const getBooth = vi.fn();
const getAiAgent = vi.fn();
const cancelMyLease = vi.fn();

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: {
    getMyBooth: () => getMyBooth(),
    cancelMyLease: (...args: unknown[]) => cancelMyLease(...args),
  },
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
// bridge 를 통째로 목하지 않는다 — 한 겹 아래 인스턴스만 세우면 slotId 변환과 SendMessage payload 까지
// 실제 코드가 돈다 (publishReload.test.tsx 와 같은 방식, S15P21A604-786).
const sendMessage = vi.fn();
let unityInstance: { SendMessage: typeof sendMessage } | null = null;
vi.mock('../../../../unity/host/sessionManager', () => ({
  getReadyUnityInstance: () => unityInstance,
}));

// jsdom 에는 <dialog> 가 없다 — 모달이 뜨는지만 보므로 showModal 을 no-op 으로 채운다
beforeEach(() => {
  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function showModal() {
      this.setAttribute('open', '');
    };
  }
});

const future = () => new Date(Date.now() + 60_000).toISOString();
const myBooth = {
  boothId: 42,
  name: '내 부스',
  lease: { slotId: 6, slotCode: 'F11-R06', endsAt: future() },
};

beforeEach(() => {
  getMyBooth.mockReset().mockResolvedValue(myBooth);
  getBooth.mockReset().mockResolvedValue({ facade: null });
  getAiAgent.mockReset().mockResolvedValue({ agentId: 1, boothId: 42 });
  cancelMyLease.mockReset().mockResolvedValue(undefined);
  setMemberSession('member-token', future());
});
beforeEach(() => {
  sendMessage.mockClear();
  unityInstance = { SendMessage: sendMessage };
});
afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

async function renderOverlay() {
  const { BoothManagementOverlay } = await import('../../ui/BoothManagementOverlay');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidated: unknown[] = [];
  const original = client.invalidateQueries.bind(client);
  client.invalidateQueries = ((filters?: { queryKey?: unknown }) => {
    invalidated.push(filters?.queryKey);
    return original(filters as never);
  }) as typeof client.invalidateQueries;

  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <BoothManagementOverlay onClose={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, invalidated };
}

async function openCancelDialog() {
  // 반납 행은 제목+부제가 한 버튼이라(-817) 접근 이름이 문장으로 길다 — 앞부분만 본다
  fireEvent.click(await screen.findByRole('button', { name: /^부스 반납하기/ }));
  return screen.findByRole('button', { name: '반납하기' });
}

describe('부스 반납 (-753)', () => {
  it('확인 모달을 거치기 전에는 요청이 나가지 않는다', async () => {
    await renderOverlay();

    fireEvent.click(await screen.findByRole('button', { name: /^부스 반납하기/ }));

    expect(cancelMyLease).not.toHaveBeenCalled();
  });

  it('확인하면 그 슬롯으로 반납을 보내고 부스 목록과 내 부스만 다시 읽는다', async () => {
    const { invalidated } = await renderOverlay();

    fireEvent.click(await openCancelDialog());

    await waitFor(() => expect(cancelMyLease).toHaveBeenCalledWith(6));
    await waitFor(() => expect(invalidated).toContainEqual(['booth-slots']));
    expect(invalidated).toContainEqual(['my-booth']);
    // 반납에는 환불이 없어 코인이 움직이지 않는다 — 잔액을 건드리면 없는 변화를 만든다
    expect(invalidated).not.toContainEqual(['wallet-balance']);
  });

  it('404 ACTIVE_LEASE_NOT_FOUND 는 배너 없이 재조회만 한다', async () => {
    cancelMyLease.mockRejectedValue({ code: 'ACTIVE_LEASE_NOT_FOUND', message: '없습니다' });
    const { invalidated } = await renderOverlay();

    fireEvent.click(await openCancelDialog());

    await waitFor(() => expect(invalidated).toContainEqual(['my-booth']));
    expect(screen.queryByText('반납하지 못했습니다. 잠시 후 다시 시도해 주세요.')).toBeNull();
  });

  it('재시도한 반납도 같은 404 경로를 탄다 — 두 번째도 배너가 없다', async () => {
    cancelMyLease.mockResolvedValueOnce(undefined).mockRejectedValue({
      code: 'ACTIVE_LEASE_NOT_FOUND',
      message: '없습니다',
    });
    await renderOverlay();

    fireEvent.click(await openCancelDialog());
    await waitFor(() => expect(cancelMyLease).toHaveBeenCalledTimes(1));

    fireEvent.click(await openCancelDialog());

    await waitFor(() => expect(cancelMyLease).toHaveBeenCalledTimes(2));
    expect(screen.queryByText('반납하지 못했습니다. 잠시 후 다시 시도해 주세요.')).toBeNull();
  });

  it('MEMBER_ONLY 같은 그 밖의 실패는 그대로 말한다', async () => {
    cancelMyLease.mockRejectedValue({ code: 'MEMBER_ONLY', message: '회원만' });
    await renderOverlay();

    fireEvent.click(await openCancelDialog());

    expect(await screen.findByText('반납하지 못했습니다. 잠시 후 다시 시도해 주세요.')).toBeTruthy();
  });
});

// 반납 뒤 상주 월드 전파 (GitLab #199 게임 파트 요청). 이것이 없으면 반납해도 다른 접속자
// 화면에는 직원·조명·포털이 켜진 채 남는다.
describe('반납 성공 → 월드 슬롯 재조회 알림 (#199)', () => {
  it('204 뒤 그 슬롯으로 재조회를 1회 알린다', async () => {
    await renderOverlay();

    fireEvent.click(await openCancelDialog());

    await waitFor(() => expect(sendMessage).toHaveBeenCalledTimes(1));
    expect(sendMessage).toHaveBeenCalledWith('BoothLayoutBridge', 'ReloadBoothSlot', '6');
  });

  it('404 ACTIVE_LEASE_NOT_FOUND 에서는 알리지 않는다 — 반납 사건이 아니다', async () => {
    cancelMyLease.mockRejectedValue({ code: 'ACTIVE_LEASE_NOT_FOUND', message: '없습니다' });
    const { invalidated } = await renderOverlay();

    fireEvent.click(await openCancelDialog());

    await waitFor(() => expect(invalidated).toContainEqual(['my-booth']));
    expect(sendMessage).not.toHaveBeenCalled();
  });

  it('확인 모달을 거치기 전에는 알리지 않는다', async () => {
    await renderOverlay();

    fireEvent.click(await screen.findByRole('button', { name: /^부스 반납하기/ }));

    expect(sendMessage).not.toHaveBeenCalled();
  });

  it('월드가 떠 있지 않으면 조용히 건너뛴다 — 반납과 재조회는 그대로다', async () => {
    unityInstance = null;
    const { invalidated } = await renderOverlay();

    fireEvent.click(await openCancelDialog());

    await waitFor(() => expect(cancelMyLease).toHaveBeenCalledWith(6));
    await waitFor(() => expect(invalidated).toContainEqual(['booth-slots']));
    expect(sendMessage).not.toHaveBeenCalled();
  });
});
