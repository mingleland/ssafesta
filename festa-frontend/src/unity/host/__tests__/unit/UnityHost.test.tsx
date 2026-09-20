// @vitest-environment jsdom
// UnityHost boot watchdog 수명주기 (S15P21A604-426, #128 §3) — 타임아웃은 boot attempt 의 시간이지 사용자의
// 페이지 체류 시간이 아니다. 로비·게이트 대기에서 실패 안내가 뜨지 않아야 하고, boot 가 멈추면 드러나야 한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import type { UnityInstance, UnityProgressListener } from '../../types';
import { UNITY_BOOT_STALL_TIMEOUT_MS } from '../../../../shared/config/unity';

type Boot = { canvas: HTMLCanvasElement; onProgress: UnityProgressListener; resolve: (i: UnityInstance) => void; reject: (e: unknown) => void };
const boots: Boot[] = [];

vi.mock('../../sessionManager', () => {
  const start = (canvas: HTMLCanvasElement, onProgress: UnityProgressListener) =>
    new Promise<UnityInstance>((resolve, reject) => { boots.push({ canvas, onProgress, resolve, reject }); });
  return { acquireUnitySession: start, restartUnitySession: start, releaseUnitySession: () => {} };
});

const instance = (): UnityInstance => ({ SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: async () => {} });
const failedText = () => screen.queryByText('월드를 불러오지 못했습니다.');
const bootingText = () => screen.queryByText(/불러오는 중/);
const gateReady = () => act(() => { window.FestaUnity?.onWorldGateReady?.(); });
const advance = (ms: number) => act(() => { vi.advanceTimersByTime(ms); });
const HALF = UNITY_BOOT_STALL_TIMEOUT_MS / 2;

beforeEach(() => {
  boots.length = 0;
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

async function renderHost() {
  const { UnityHost } = await import('../../UnityHost');
  render(<UnityHost />);
  expect(boots).toHaveLength(1);
  return boots[0];
}

describe('UnityHost boot watchdog (-426)', () => {
  it('인스턴스가 서면 watchdog 이 풀린다 — 로비·게이트 대기가 아무리 길어도 실패 안내가 뜨지 않는다', async () => {
    const boot = await renderHost();
    await act(async () => { boot.resolve(instance()); });
    expect(bootingText()).toBeNull();
    advance(UNITY_BOOT_STALL_TIMEOUT_MS * 10);
    expect(failedText()).toBeNull();
    gateReady();
    expect(failedText()).toBeNull();
  });

  it('boot 중 진행률이 멈춘 채 UNITY_BOOT_STALL_TIMEOUT_MS 가 지나면 failed', async () => {
    await renderHost();
    advance(UNITY_BOOT_STALL_TIMEOUT_MS);
    expect(failedText()).not.toBeNull();
  });

  it('진행률이 올 때마다 다시 잰다 — 느리지만 진행 중인 다운로드는 실패가 아니다', async () => {
    const boot = await renderHost();
    advance(HALF);
    act(() => { boot.onProgress(0.3); });
    advance(HALF);
    expect(failedText()).toBeNull();
    advance(HALF);
    expect(failedText()).not.toBeNull();
  });

  it('다시 시도는 새 boot attempt 와 새 watchdog 을 만든다', async () => {
    await renderHost();
    advance(UNITY_BOOT_STALL_TIMEOUT_MS);
    expect(failedText()).not.toBeNull();
    act(() => { screen.getByRole('button', { name: '다시 시도' }).click(); });
    expect(boots).toHaveLength(2);
    expect(failedText()).toBeNull();
    await act(async () => { boots[1].resolve(instance()); });
    advance(UNITY_BOOT_STALL_TIMEOUT_MS * 10);
    expect(failedText()).toBeNull();
  });

  it('boot 자체가 거부되면 즉시 failed', async () => {
    const boot = await renderHost();
    await act(async () => { boot.reject(new Error('loader')); });
    expect(failedText()).not.toBeNull();
  });
});
