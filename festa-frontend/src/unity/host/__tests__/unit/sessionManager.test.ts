// sessionManager의 single-flight(B-2)·retry 직렬화(B-3) 회귀 방어
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { UnityInstance } from '../../types';

const loadUnityBuild = vi.fn();
vi.mock('../../loader.select', () => ({ loadUnityBuild: (...args: unknown[]) => loadUnityBuild(...args) }));

// 매 테스트 후 모듈 상태를 새로 읽어야 mock 초기화가 반영된다 — vi.resetModules + 동적 import
async function freshSessionManager() {
  vi.resetModules();
  return import('../../sessionManager');
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (err: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function fakeInstance(quit: () => Promise<void>): UnityInstance {
  return { SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: quit };
}

const canvas = {} as HTMLCanvasElement;
const noopProgress = () => {};

beforeEach(() => {
  loadUnityBuild.mockReset();
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('acquireUnitySession — single-flight (B-2)', () => {
  it('생성 중에 다시 acquire하면 loadUnityBuild를 또 부르지 않고 같은 promise를 돌려준다', async () => {
    const { acquireUnitySession } = await freshSessionManager();
    const gate = deferred<UnityInstance>();
    loadUnityBuild.mockReturnValueOnce(gate.promise);

    const first = acquireUnitySession(canvas, noopProgress);
    const second = acquireUnitySession(canvas, noopProgress);

    expect(loadUnityBuild).toHaveBeenCalledTimes(1);
    const instance = fakeInstance(() => Promise.resolve());
    gate.resolve(instance);

    await expect(first).resolves.toBe(instance);
    await expect(second).resolves.toBe(instance);
  });

  it('StrictMode의 mount→cleanup→mount — release 예약 직후 다시 acquire하면 Quit이 불리지 않는다', async () => {
    const { acquireUnitySession, releaseUnitySession } = await freshSessionManager();
    const quit = vi.fn(() => Promise.resolve());
    loadUnityBuild.mockResolvedValueOnce(fakeInstance(quit));

    await acquireUnitySession(canvas, noopProgress); // ready

    releaseUnitySession(); // cleanup(1차 mount) — 예약만 한다
    acquireUnitySession(canvas, noopProgress); // 2차 mount — 예약을 취소해야 한다

    await vi.runAllTimersAsync(); // 취소되지 않았다면 여기서 Quit이 실행된다

    expect(quit).not.toHaveBeenCalled();
  });

  it('진짜 언마운트(재acquire 없음) — 예약된 시간이 지나면 Quit이 실행된다', async () => {
    const { acquireUnitySession, releaseUnitySession } = await freshSessionManager();
    const quit = vi.fn(() => Promise.resolve());
    loadUnityBuild.mockResolvedValueOnce(fakeInstance(quit));

    await acquireUnitySession(canvas, noopProgress);
    releaseUnitySession();

    await vi.runAllTimersAsync();

    expect(quit).toHaveBeenCalledTimes(1);
  });
});

describe('restartUnitySession — retry 직렬화 (B-3)', () => {
  it('기존 인스턴스의 Quit이 끝나기 전에는 새 인스턴스를 만들지 않는다', async () => {
    const { acquireUnitySession, restartUnitySession } = await freshSessionManager();
    const quitGate = deferred<void>();
    const quit = vi.fn(() => quitGate.promise);
    loadUnityBuild.mockResolvedValueOnce(fakeInstance(quit));

    await acquireUnitySession(canvas, noopProgress); // 첫 인스턴스 ready

    const secondGate = deferred<UnityInstance>();
    loadUnityBuild.mockReturnValueOnce(secondGate.promise);

    const restart = restartUnitySession(canvas, noopProgress);
    await Promise.resolve(); // restartUnitySession 내부 microtask 진행

    expect(quit).toHaveBeenCalledTimes(1);
    expect(loadUnityBuild).toHaveBeenCalledTimes(1); // 아직 두 번째 생성 호출 안 됨 — Quit 대기 중

    quitGate.resolve(); // 종료 완료
    await Promise.resolve();
    await Promise.resolve();

    expect(loadUnityBuild).toHaveBeenCalledTimes(2); // 이제야 새 인스턴스 생성 시작

    const secondInstance = fakeInstance(() => Promise.resolve());
    secondGate.resolve(secondInstance);

    await expect(restart).resolves.toBe(secondInstance);
  });

  it('생성 중 실패하면 다음 acquire는 새로 생성을 시도한다', async () => {
    const { acquireUnitySession } = await freshSessionManager();
    loadUnityBuild.mockRejectedValueOnce(new Error('load failed'));

    await expect(acquireUnitySession(canvas, noopProgress)).rejects.toThrow('load failed');

    const instance = fakeInstance(() => Promise.resolve());
    loadUnityBuild.mockResolvedValueOnce(instance);
    await expect(acquireUnitySession(canvas, noopProgress)).resolves.toBe(instance);
    expect(loadUnityBuild).toHaveBeenCalledTimes(2);
  });
});
