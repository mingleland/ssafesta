// @vitest-environment jsdom
// 게시 성공 → 상주 Unity 에 슬롯 재조회 알림 (S15P21A604-644).
//
// 게시는 게시본 version 을 바꾼다 — 이 seam 의 원래 용도다(게임 파트 QA 09-08 #11). draft 저장은
// Unity 가 읽지 않으므로 알리지 않는다 — 그 경계를 여기서 함께 잠근다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { MyBooth } from '../../../../entities/booth/types';

const publish = vi.fn();
const putDraft = vi.fn();
vi.mock('../../../../entities/layout/api.select', () => ({
  layoutApi: { publish: (id: number) => publish(id), putDraft: (id: number, body: unknown) => putDraft(id, body) },
}));
// bridge 를 통째로 목하지 않는다 (S15P21A604-786). 한 겹 아래인 Unity 인스턴스만 세우면
// **slotId 조회와 SendMessage payload 까지 실제 코드가 돈다** — 알림 경로를 리팩터링해도
// 이 테스트가 계속 같은 것을 지킨다.
const sendMessage = vi.fn();
let instance: { SendMessage: typeof sendMessage } | null = { SendMessage: sendMessage };
vi.mock('../../../../unity/host/sessionManager', () => ({ getReadyUnityInstance: () => instance }));

const { usePublish, useSaveDraft } = await import('../../model/useLayoutMutations');

const MY_BOOTH: MyBooth = {
  boothId: 2, name: '내 부스', status: 'ACTIVE',
  lease: { leaseId: 3, slotId: 6, slotCode: 'F11-R06', startsAt: '', endsAt: '', remainingSeconds: 0, chargedCoin: 100 },
};

function setup(myBooth: MyBooth | undefined) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  if (myBooth) client.setQueryData(['my-booth'], myBooth);
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return { wrapper, dispatch: vi.fn() };
}

beforeEach(() => { publish.mockReset(); putDraft.mockReset(); sendMessage.mockClear(); instance = { SendMessage: sendMessage }; });
afterEach(() => { vi.restoreAllMocks(); });

describe('게시 성공', () => {
  it('my-booth 캐시의 slotId 로 재조회를 1회 알린다', async () => {
    publish.mockResolvedValue({ publishedVersion: 3 });
    const { wrapper, dispatch } = setup(MY_BOOTH);
    const { result } = renderHook(() => usePublish(dispatch), { wrapper });
    result.current.mutate(2);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(sendMessage).toHaveBeenCalledTimes(1);
    expect(sendMessage).toHaveBeenCalledWith('BoothLayoutBridge', 'ReloadBoothSlot', '6');
    expect(dispatch).toHaveBeenCalledWith({ type: 'PUBLISH_SUCCESS', publishedVersion: 3 });
  });

  it('slotId 를 모르면 조용히 건너뛴다 — 다음 월드 진입이 읽는다', async () => {
    publish.mockResolvedValue({ publishedVersion: 3 });
    const { wrapper, dispatch } = setup(undefined);
    const { result } = renderHook(() => usePublish(dispatch), { wrapper });
    result.current.mutate(2);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(sendMessage).not.toHaveBeenCalled();
    expect(dispatch).toHaveBeenCalled(); // 게시 자체는 그대로 성공 처리된다
  });

  it('게시가 실패하면 알리지 않는다', async () => {
    publish.mockRejectedValue(new Error('boom'));
    const { wrapper, dispatch } = setup(MY_BOOTH);
    const { result } = renderHook(() => usePublish(dispatch), { wrapper });
    result.current.mutate(2);
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(sendMessage).not.toHaveBeenCalled();
  });

  it('월드에 들어가 있지 않으면 조용히 건너뛴다 — 게시는 그대로 성공한다', async () => {
    publish.mockResolvedValue({ publishedVersion: 3 });
    instance = null;
    const { wrapper, dispatch } = setup(MY_BOOTH);
    const { result } = renderHook(() => usePublish(dispatch), { wrapper });
    result.current.mutate(2);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(sendMessage).not.toHaveBeenCalled();
    expect(dispatch).toHaveBeenCalledWith({ type: 'PUBLISH_SUCCESS', publishedVersion: 3 });
  });
});

describe('draft 저장', () => {
  it('알리지 않는다 — Unity 는 draft 를 읽지 않고, 보내도 version 이 같아 아무 일이 없다', async () => {
    putDraft.mockResolvedValue({ revision: 5 });
    const { wrapper, dispatch } = setup(MY_BOOTH);
    const { result } = renderHook(() => useSaveDraft(dispatch), { wrapper });
    result.current.mutate({ boothId: 2, expectedRevision: 4, template: 'PROJECT_EXHIBITION', objects: [] });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(sendMessage).not.toHaveBeenCalled();
  });
});
