// @vitest-environment jsdom
// G-8 입력 소유권 (S15P21A604-450, GitLab #132) — React 오버레이와 Unity 캔버스 중 누가 키보드를 갖는가.
// 계약은 Unity 가 소유하고(InputBridge, S15P21A604-434 !279) FE 는 개폐를 그 계약으로 통지하기만 한다.
// 여기서 잠그는 것: ① 통지 인자가 계약과 정확히 같은가 ② 진입 직후 캔버스가 focus 를 받는가
// ③ 오버레이가 주인일 때 FE 가 focus 를 뺏지 않는가 ④ FE 가 자체 키보드 판정을 만들지 않는가.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import type { UnityInstance, UnityProgressListener } from '../../types';
import { closeOverlay, openOverlay } from '../../../../shared/types/overlay';

type Boot = { resolve: (i: UnityInstance) => void };
const boots: Boot[] = [];

vi.mock('../../sessionManager', () => {
  const start = (_c: HTMLCanvasElement, _p: UnityProgressListener) =>
    new Promise<UnityInstance>((resolve) => { boots.push({ resolve }); });
  return { acquireUnitySession: start, restartUnitySession: start, releaseUnitySession: () => {} };
});
// 이 테스트의 관심사는 입력 소유권이다 — 토큰 주입은 같은 인스턴스를 공유하므로 통지 호출만 남기고 뺀다.
vi.mock('../../authBridge', () => ({ syncAccessToken: () => 'cleared' }));

const instance = (): UnityInstance => ({ SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: async () => {} });
const lockCalls = (i: UnityInstance) =>
  (i.SendMessage as ReturnType<typeof vi.fn>).mock.calls.filter((c) => c[1] === 'SetInputLocked');

beforeEach(() => { boots.length = 0; closeOverlay(); });
afterEach(() => { cleanup(); closeOverlay(); });

async function renderBooted() {
  const { UnityHost } = await import('../../UnityHost');
  const inst = instance();
  const view = render(<UnityHost />);
  await act(async () => { boots[0].resolve(inst); });
  return { inst, view };
}

describe('입력 소유권 배선 (-450)', () => {
  it('인스턴스가 서면 잠금 해제 상태를 먼저 알린다 — 새 인스턴스는 잠금 상태를 모른다', async () => {
    const { inst } = await renderBooted();
    expect(lockCalls(inst)).toEqual([['InputBridge', 'SetInputLocked', '0']]);
  });

  it('오버레이가 열리면 계약 그대로 잠금을 보낸다', async () => {
    const { inst } = await renderBooted();
    act(() => { openOverlay('AI_CHAT', { boothId: 1, agentId: 2 }); });
    expect(lockCalls(inst).at(-1)).toEqual(['InputBridge', 'SetInputLocked', '1']);
  });

  it('오버레이가 닫히면 잠금을 푼다', async () => {
    const { inst } = await renderBooted();
    act(() => { openOverlay('SURVEY', { boothId: 3 }); });
    act(() => { closeOverlay(); });
    expect(lockCalls(inst).at(-1)).toEqual(['InputBridge', 'SetInputLocked', '0']);
  });

  it('오버레이 종류가 바뀌어도 잠금을 풀었다 걸지 않는다 — 계속 잠긴 상태다', async () => {
    const { inst } = await renderBooted();
    act(() => { openOverlay('AI_CHAT', { boothId: 1 }); });
    const afterOpen = lockCalls(inst).length;
    act(() => { openOverlay('LAPTOP', { boothId: 1, objectId: 'laptop-01' }); });
    // 잠금 값이 그대로 '1' 이라 중간에 '0' 이 끼어들지 않는다
    expect(lockCalls(inst).slice(afterOpen).every((c) => c[2] === '1')).toBe(true);
  });

  it('최초 월드 진입 시 캔버스가 focus 를 받는다 — 첫 키 입력이 무시되지 않는다', async () => {
    const { view } = await renderBooted();
    const canvas = view.container.querySelector('canvas');
    expect(document.activeElement).toBe(canvas);
  });

  it('오버레이가 열린 채로 인스턴스가 서면 focus 를 뺏지 않는다 — 그쪽이 키보드 주인이다', async () => {
    openOverlay('CONSULTATION', { boothId: 5 });
    const { view } = await renderBooted();
    const canvas = view.container.querySelector('canvas');
    expect(document.activeElement).not.toBe(canvas);
  });

  it('FE 는 키보드 판정을 갖지 않는다 — 잠금 통지 말고 다른 입력 관련 SendMessage 를 만들지 않는다', async () => {
    const { inst } = await renderBooted();
    act(() => { openOverlay('GAME', { gameId: 'g1' }); });
    const methods = new Set(
      (inst.SendMessage as ReturnType<typeof vi.fn>).mock.calls.map((c) => c[1] as string),
    );
    expect([...methods]).toEqual(['SetInputLocked']);
  });
});
