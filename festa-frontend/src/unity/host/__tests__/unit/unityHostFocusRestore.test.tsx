// @vitest-environment jsdom
// 상주 월드로 돌아올 때 캔버스 focus 복구 (S15P21A604-643).
//
// -620 이전에는 라우트 복귀 = UnityHost 재마운트 = 진입 focus 효과 재실행이었다. 지금은 마운트가
// 유지되므로 worldMount.visible 전이를 따로 본다 — 안 보면 /app/profile 에서 돌아온 뒤 캔버스를
// 클릭하기 전까지 WASD 가 죽어 있다(2026-09-11 5173 실측).
//
// 잠그는 것: ① hidden → visible 에 focus ② hidden 인 동안 0 ③ 미준비에서 throw 0 ④ 오버레이가
// 주인이면 탈취 0 ⑤ React 입력창이 쥐고 있으면 침범 0 ⑥ 다시 보일 때 boot 를 다시 걸지 않는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import type { UnityInstance, UnityProgressListener } from '../../types';
import { closeOverlay, openOverlay } from '../../../../shared/types/overlay';
import { __resetGameClientUiForTests } from '../../../../features/world/model/gameClientUi';
import { __resetWorldMountForTests, hideWorld, showWorld } from '../../worldMount';

type Boot = { resolve: (i: UnityInstance) => void };
const boots: Boot[] = [];
const acquire = vi.fn((_c: HTMLCanvasElement, _p: UnityProgressListener) =>
  new Promise<UnityInstance>((resolve) => { boots.push({ resolve }); }));
vi.mock('../../sessionManager', () => ({
  acquireUnitySession: (c: HTMLCanvasElement, p: UnityProgressListener) => acquire(c, p),
  restartUnitySession: (c: HTMLCanvasElement, p: UnityProgressListener) => acquire(c, p),
  releaseUnitySession: () => {},
}));
// 이 테스트의 관심사는 focus 하나다 — 같은 인스턴스를 쓰는 다른 동기화는 뺀다
vi.mock('../../authBridge', () => ({ syncAccessToken: () => 'cleared' }));
vi.mock('../../audioBridge', () => ({ syncAudioMute: () => {}, syncAudioVolume: () => {} }));
vi.mock('../../inputBridge', () => ({ syncInputLock: () => {} }));

const instance = (): UnityInstance => ({ SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: async () => {} });
const canvas = () => document.querySelector('#unity-canvas') as HTMLCanvasElement;

beforeEach(() => {
  boots.length = 0;
  acquire.mockClear();
  closeOverlay();
  __resetGameClientUiForTests();
  __resetWorldMountForTests();
  showWorld(); // 월드 화면에 있는 상태에서 호스트가 뜬다
});
afterEach(() => {
  cleanup();
  closeOverlay();
  __resetGameClientUiForTests();
  __resetWorldMountForTests();
});

async function renderBooted() {
  const { UnityHost } = await import('../../UnityHost');
  const view = render(<UnityHost />);
  await act(async () => { boots[0].resolve(instance()); });
  return view;
}

describe('다시 보일 때', () => {
  it('hidden → visible 이면 캔버스가 focus 를 받는다 — 클릭 없이 WASD 가 산다', async () => {
    await renderBooted();
    expect(document.activeElement).toBe(canvas()); // 진입 focus (-450)

    act(() => hideWorld());
    (document.activeElement as HTMLElement | null)?.blur();
    expect(document.activeElement).toBe(document.body); // 라우트 복귀 직후의 실제 상태

    act(() => showWorld());
    expect(document.activeElement).toBe(canvas());
  });

  it('hidden 인 동안에는 focus 를 건드리지 않는다', async () => {
    await renderBooted();
    act(() => hideWorld());
    (document.activeElement as HTMLElement | null)?.blur();

    const spy = vi.spyOn(canvas(), 'focus');
    // hidden 상태에서 store 가 같은 값으로 다시 emit 되어도
    act(() => hideWorld());
    expect(spy).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(document.body);
  });

  it('인스턴스가 서기 전의 visible 전이는 아무 일도 하지 않는다', async () => {
    const { UnityHost } = await import('../../UnityHost');
    render(<UnityHost />);
    act(() => hideWorld());
    expect(() => act(() => showWorld())).not.toThrow();
    expect(document.activeElement).not.toBe(canvas());
  });

  it('오버레이가 떠 있으면 뺏지 않는다 — 그쪽이 키보드 주인이다', async () => {
    await renderBooted();
    act(() => hideWorld());
    (document.activeElement as HTMLElement | null)?.blur();
    act(() => openOverlay('LAPTOP', { boothId: 1, objectId: 'x' }));

    act(() => showWorld());
    expect(document.activeElement).not.toBe(canvas());
  });

  it('React 입력창이 focus 를 쥐고 있으면 침범하지 않는다', async () => {
    await renderBooted();
    act(() => hideWorld());
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    expect(document.activeElement).toBe(input);

    act(() => showWorld());
    expect(document.activeElement).toBe(input);
    input.remove();
  });

  it('다시 보일 때 Unity 를 다시 띄우지 않는다', async () => {
    await renderBooted();
    expect(acquire).toHaveBeenCalledTimes(1);
    act(() => hideWorld());
    act(() => showWorld());
    act(() => hideWorld());
    act(() => showWorld());
    expect(acquire).toHaveBeenCalledTimes(1);
  });
});
