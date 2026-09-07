// @vitest-environment jsdom
// 백그라운드 복귀 재접속 UX (S15P21A604-432, GitLab #131) — Unity 가 끊김 감지·백오프 재시도·포기까지
// 스스로 하고(WorldReconnector) 호스트는 그 상태만 표시한다. 이 테스트는 ① 표시가 상태를 따라가는지
// ② 사용자가 직접 끊은 경우를 재접속으로 오인하지 않는지 ③ 실패 시 복구 동선이 있는지를 잠근다.
// FE 가 재시도 타이머를 갖지 않는다는 경계도 함께 지킨다 — 타이머를 돌려도 상태가 저절로 바뀌지 않아야 한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { UnityInstance, UnityProgressListener } from '../../types';
import { UNITY_BOOT_STALL_TIMEOUT_MS, WORLD_PREPARING_LONG_WAIT_MS } from '../../../../shared/config/unity';

type Boot = { resolve: (i: UnityInstance) => void };
const boots: Boot[] = [];

vi.mock('../../sessionManager', () => {
  const start = (_c: HTMLCanvasElement, _p: UnityProgressListener) =>
    new Promise<UnityInstance>((resolve) => { boots.push({ resolve }); });
  return { acquireUnitySession: start, restartUnitySession: start, releaseUnitySession: () => {} };
});

const instance = (): UnityInstance => ({ SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: async () => {} });
const advance = (ms: number) => act(() => { vi.advanceTimersByTime(ms); });
const gateReady = () => act(() => { window.FestaUnity?.onWorldGateReady?.(); });
const loadStart = () => act(() => { window.FestaUnity?.onWorldLoadStart?.(); });
const connection = (state: string, detail = '') =>
  act(() => { window.FestaUnity?.onWorldConnectionState?.(state, detail); });

beforeEach(() => { boots.length = 0; vi.useFakeTimers(); });
afterEach(() => { cleanup(); vi.useRealTimers(); });

async function renderInWorld() {
  const { UnityHost } = await import('../../UnityHost');
  const view = render(<UnityHost />);
  await act(async () => { boots[0].resolve(instance()); });
  gateReady();   // 월드 진입 완료 — 이 뒤가 접속 상태가 의미 있는 구간이다
  return view;
}

describe('월드 접속 상태 UX (-432)', () => {
  it('신호가 없으면 아무 안내도 띄우지 않는다 — 신호 유무에 기존 동작이 묶이지 않는다', async () => {
    await renderInWorld();
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('끊기면 곧바로 실패로 넘기지 않고 재접속 안내를 띄운다', async () => {
    await renderInWorld();
    connection('disconnected', '');
    expect(screen.getByText('재접속 중…')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('재시도 회차가 올라와도 같은 재접속 안내를 유지한다 — 회차 상한을 FE 가 가정하지 않는다', async () => {
    await renderInWorld();
    connection('reconnecting', '1');
    expect(screen.getByText('재접속 중…')).toBeTruthy();
    connection('reconnecting', '5');
    expect(screen.getByText('재접속 중…')).toBeTruthy();
  });

  it('재접속에 성공하면 안내가 사라진다', async () => {
    await renderInWorld();
    connection('reconnecting', '1');
    connection('connected', '');
    expect(screen.queryByText('재접속 중…')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('사용자가 직접 끊은 것은 재접속으로 오인하지 않는다', async () => {
    await renderInWorld();
    connection('disconnected', 'USER');
    expect(screen.queryByText('재접속 중…')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('포기하면 사유와 복구 동선을 함께 보여준다', async () => {
    await renderInWorld();
    connection('failed', 'INVALID_TOKEN');
    expect(screen.getByRole('alert')).toBeTruthy();
    expect(screen.getByText('로그인이 만료되었습니다. 다시 로그인해 주세요.')).toBeTruthy();
    expect(screen.getByRole('button', { name: '로비로 돌아가기' })).toBeTruthy();
  });

  it('모르는 사유가 와도 화면이 비지 않는다 — Unity 가 사유를 늘려도 안전하다', async () => {
    await renderInWorld();
    connection('failed', 'START_CLIENT_FAILED');
    expect(screen.getByText('연결을 다시 세우지 못했습니다.')).toBeTruthy();
  });

  it('FE 는 재시도 타이머를 갖지 않는다 — 시간이 흘러도 상태가 저절로 바뀌지 않는다', async () => {
    await renderInWorld();
    connection('reconnecting', '1');
    advance(WORLD_PREPARING_LONG_WAIT_MS * 10);
    expect(screen.getByText('재접속 중…')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('접속 안내는 월드 로딩 안내를 덮는다 — 두 안내가 겹치지 않는다', async () => {
    const { UnityHost } = await import('../../UnityHost');
    render(<UnityHost />);
    await act(async () => { boots[0].resolve(instance()); });
    loadStart();
    expect(screen.getByText('축제장을 불러오고 있어요')).toBeTruthy();
    connection('reconnecting', '1');
    expect(screen.queryByText('축제장을 불러오고 있어요')).toBeNull();
    expect(screen.getByText('재접속 중…')).toBeTruthy();
  });

  it('언마운트 뒤 신호가 와도 크래시하지 않는다 — 구독이 실제로 끊긴다', async () => {
    const view = await renderInWorld();
    view.unmount();
    expect(() => connection('failed', 'SERVER_FULL')).not.toThrow();
  });
});

// 상태 층은 pointer-events:none 이라 월드 클릭을 가로채지 않는다. 그 안의 복구 버튼만 예외로
// 되살려야 하는데, 클래스가 빠져 있어 **버튼이 보이기만 하고 눌리지 않았다**(2026-09-06 실브라우저 실측).
// jsdom 은 pointer-events 를 계산하지 않으므로 클래스 부착과 동작 두 가지로 잠근다.
describe('복구 동선 버튼 (-432)', () => {
  it('failed 버튼이 상태 층 조작 클래스를 단다 — 부모의 pointer-events:none 을 되돌리는 유일한 지점', async () => {
    await renderInWorld();
    connection('failed', 'SERVER_FULL');
    const button = screen.getByRole('button', { name: '로비로 돌아가기' });
    expect(button.className).toContain('uh-status-action');
  });

  it('누르면 새 boot attempt 가 시작된다 — 화면만 바뀌고 끝나지 않는다', async () => {
    await renderInWorld();
    connection('failed', 'SERVER_FULL');
    const before = boots.length;

    fireEvent.click(screen.getByRole('button', { name: '로비로 돌아가기' }));

    expect(boots.length).toBe(before + 1);
    expect(screen.queryByRole('alert')).toBeNull(); // 재시도하면 실패 화면이 걷힌다
  });

  it('boot 실패 화면도 같은 상태 층 어휘를 쓴다 — 맨 div 로 월드 위에 글자만 뜨지 않는다', async () => {
    const { UnityHost } = await import('../../UnityHost');
    render(<UnityHost />);
    advance(UNITY_BOOT_STALL_TIMEOUT_MS + 1000);
    const alert = screen.getByRole('alert');
    expect(alert.className).toContain('uh-status');
    expect(screen.getByRole('button', { name: '다시 시도' }).className).toContain('uh-status-action');
  });
});
