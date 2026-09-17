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
  lease: { slotId: 6, slotCode: 'F11-R06', endsAt: future(), chargedCoin: 100 },
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
  return screen.findByRole('button', { name: '반납' });
}

/** 확인 모달만 본다 — 관리 화면 본문에는 슬롯 코드가 정상적으로 떠 있다 */
const cancelDialog = () => document.querySelector('dialog.lease-cancel') as HTMLDialogElement;

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

// 이 모달은 OverlayFrame 의 형제라 .festa-overlay 안이 아니다. 토큰 선언부가 .lease-confirm 을
// 직접 받지 않으면 월드에서 열었을 때 배경·테두리·글꼴이 통째로 무효가 된다(2026-09-17).
// jsdom 은 커스텀 속성을 계산하지 않으므로 선언부 자체를 읽어 잠근다.
describe('반납 모달 토큰 스코프', () => {
  it('--sc-* 선언이 .lease-confirm 을 포함한다', async () => {
    const { readFileSync } = await import('node:fs');
    const css = readFileSync('src/features/shell/ui/pageShell.css', 'utf8');
    const selectors = css.slice(0, css.indexOf('--sc-bg:'));
    expect(selectors).toContain('.lease-confirm');
  });
});

// 모달이 말하는 것은 셋뿐이다 — 무엇을 / 지금 무슨 일이 / 돈은 (2026-09-17).
// Booth Studio 폐기로 사실이 아니게 된 보존·Draft 안내와 내부 슬롯 ID 노출을 함께 잠근다.
describe('반납 모달 내용', () => {
  it('BE 부스명을 보여 주고 내부 슬롯 ID 는 노출하지 않는다', async () => {
    await renderOverlay();
    await openCancelDialog();

    const dialog = cancelDialog();
    expect(dialog.textContent).toContain('내 부스');
    expect(dialog.textContent).not.toContain('F11-R06');
    // 관리 화면 본문에는 그대로 있어야 한다 — 모달에서만 뺀 것이다
    expect(document.body.textContent).toContain('F11-R06');
  });

  it('부스명이 없으면 중립 문구로 떨어진다', async () => {
    getMyBooth.mockResolvedValue({ ...myBooth, name: null });
    await renderOverlay();
    await openCancelDialog();

    expect(cancelDialog().textContent).toContain('현재 부스');
  });

  it('실제 차감액과 즉시 종료만 말한다 — Booth Studio 시절 문구는 없다', async () => {
    await renderOverlay();
    await openCancelDialog();

    const text = cancelDialog().textContent ?? '';
    expect(text).toContain('부스를 반납할까요?');
    expect(text).toContain('반납 즉시 이용이 종료됩니다.');
    expect(text).toContain('사용한 100코인은 환불되지 않습니다.');
    expect(text).not.toContain('Draft');
    expect(text).not.toContain('그대로 남습니다');
    expect(text).not.toContain('배치');
  });

  it('버튼은 반납 → 취소 순서이고 포커스는 취소에 있다 — Enter 가 반납을 실행하면 안 된다', async () => {
    await renderOverlay();
    await openCancelDialog();

    const dialog = cancelDialog();
    const labels = [...dialog.querySelectorAll('button')].map((b) => b.textContent);
    expect(labels).toEqual(['반납', '취소']);
    expect(document.activeElement).toBe(dialog.querySelector('button:last-of-type'));
  });

  it('취소를 누르면 모달만 닫히고 요청은 나가지 않는다', async () => {
    await renderOverlay();
    await openCancelDialog();

    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(cancelDialog()).toBeNull());
    expect(cancelMyLease).not.toHaveBeenCalled();
  });
});
