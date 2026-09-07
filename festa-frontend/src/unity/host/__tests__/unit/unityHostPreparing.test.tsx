// @vitest-environment jsdom
// World Preparing UX (S15P21A604-429, #128) — 로비 체류는 사용자 시간이라 안내가 없어야 하고,
// 월드 로드 시작 후 50~84초 구간에는 안내가 있어야 한다. boot watchdog(장애 감지)과 섞이지 않는지도 본다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import type { UnityInstance, UnityProgressListener } from '../../types';
import { WORLD_PREPARING_LONG_WAIT_MS, UNITY_BOOT_STALL_TIMEOUT_MS } from '../../../../shared/config/unity';

type Boot = { resolve: (i: UnityInstance) => void };
const boots: Boot[] = [];

vi.mock('../../sessionManager', () => {
  const start = (_c: HTMLCanvasElement, _p: UnityProgressListener) =>
    new Promise<UnityInstance>((resolve) => { boots.push({ resolve }); });
  return { acquireUnitySession: start, restartUnitySession: start, releaseUnitySession: () => {} };
});

const instance = (): UnityInstance => ({ SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: async () => {} });
const advance = (ms: number) => act(() => { vi.advanceTimersByTime(ms); });
const loadStart = () => act(() => { window.FestaUnity?.onWorldLoadStart?.(); });
const gateReady = () => act(() => { window.FestaUnity?.onWorldGateReady?.(); });

beforeEach(() => { boots.length = 0; vi.useFakeTimers(); });
afterEach(() => { cleanup(); vi.useRealTimers(); });

async function renderReady() {
  const { UnityHost } = await import('../../UnityHost');
  render(<UnityHost />);
  await act(async () => { boots[0].resolve(instance()); });   // 인스턴스 확보 = 로비 표시 시작
}

describe('World Preparing UX (-429)', () => {
  it('로비 체류 중에는 안내가 없다 — 사용자 시간이라 방해하지 않는다', async () => {
    await renderReady();
    expect(screen.queryByRole('status')).toBeNull();
    advance(WORLD_PREPARING_LONG_WAIT_MS * 3);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('월드 로드 시작 신호를 받으면 축제장 로딩 안내가 뜬다', async () => {
    await renderReady();
    loadStart();
    expect(screen.getByText('축제장을 불러오고 있어요')).toBeTruthy();
  });

  it('길어지면 문구가 바뀌지만 실패로 넘어가지 않는다', async () => {
    await renderReady();
    loadStart();
    advance(WORLD_PREPARING_LONG_WAIT_MS);
    expect(screen.getByText('월드를 준비하고 있습니다')).toBeTruthy();
    expect(screen.getByText('잠시만 기다려 주세요.')).toBeTruthy();
    advance(UNITY_BOOT_STALL_TIMEOUT_MS * 2);
    expect(screen.queryByText('월드를 불러오지 못했습니다.')).toBeNull();
  });

  it('게이트 ready 에 안내가 즉시 사라진다', async () => {
    await renderReady();
    loadStart();
    gateReady();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('가짜 진행률을 만들지 않는다 — 로딩 구간에 백분율이 없다', async () => {
    await renderReady();
    loadStart();
    expect(screen.queryByText(/%/)).toBeNull();
  });

  it('boot 중에 온 신호는 무시한다 — 아직 로비가 아니다', async () => {
    const { UnityHost } = await import('../../UnityHost');
    render(<UnityHost />);
    loadStart();
    expect(screen.getByText('게임을 준비하고 있어요')).toBeTruthy();
    expect(screen.queryByText('축제장을 불러오고 있어요')).toBeNull();
  });

  it('ready 이후에 온 신호는 화면을 되돌리지 않는다', async () => {
    await renderReady();
    gateReady();
    loadStart();
    expect(screen.queryByRole('status')).toBeNull();
  });
});
