// @vitest-environment jsdom
// 화면 음소거 승계 (S15P21A604-557, GitLab #151) — 화면에서 끈 상태가 월드로 넘어가는가.
// 결함은 "월드에 들어가면 BGM 이 다시 난다" 였고, 원인은 -463 이 오디오 소유권만 넘기고 mute
// 선호는 넘기지 않은 것이다. 여기서 잠그는 것: ① 인스턴스가 서면 현재 값이 먼저 간다(새 인스턴스는
// 상태를 모른다) ② 이후 변경이 따라간다 ③ 미준비 상태에서는 보내지 않는다 ④ 기존 두 bridge 무회귀.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import type { UnityInstance, UnityProgressListener } from '../../types';
import { AUDIO_BRIDGE_OBJECT } from '../../audioBridge';
import {
  MUSIC_MUTED_STORAGE_KEY,
  __resetScreenAudioForTests,
  setMuted,
} from '../../../../features/audio/model/screenAudio';

type Boot = { resolve: (i: UnityInstance) => void };
const boots: Boot[] = [];

vi.mock('../../sessionManager', () => {
  const start = (_c: HTMLCanvasElement, _p: UnityProgressListener) =>
    new Promise<UnityInstance>((resolve) => { boots.push({ resolve }); });
  return { acquireUnitySession: start, restartUnitySession: start, releaseUnitySession: () => {} };
});

const instance = (): UnityInstance => ({ SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: async () => {} });
const calls = (i: UnityInstance) => (i.SendMessage as ReturnType<typeof vi.fn>).mock.calls;
const muteCalls = (i: UnityInstance) => calls(i).filter((c) => c[1] === 'SetMuted');

beforeEach(() => {
  boots.length = 0;
  // 이 테스트는 오디오 재생이 아니라 전달 배선을 본다. Audio 가 없으면 screenAudio 는 element 를
  // 만들지 않고 조용히 빠진다(ensureElement 의 "테스트 node 환경 등" 경로) — jsdom 의 미구현
  // HTMLMediaElement 재생을 흉내 낼 이유가 없다.
  vi.stubGlobal('Audio', undefined);
  window.localStorage.clear();
  __resetScreenAudioForTests();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  __resetScreenAudioForTests();
});

async function renderBooted() {
  const { UnityHost } = await import('../../UnityHost');
  const inst = instance();
  const view = render(<UnityHost />);
  await act(async () => { boots[0].resolve(inst); });
  return { inst, view };
}

/** 인스턴스가 서기 전 단계까지만 진행한다 — boot 를 resolve 하지 않는다. */
async function renderBooting() {
  const { UnityHost } = await import('../../UnityHost');
  const inst = instance();
  const view = render(<UnityHost />);
  return { inst, view };
}

describe('음소거 승계 배선 (-557, #151)', () => {
  it('인스턴스가 서면 현재 값을 먼저 알린다 — 새 인스턴스는 음소거 상태를 모른다', async () => {
    const { inst } = await renderBooted();
    expect(muteCalls(inst)).toEqual([[AUDIO_BRIDGE_OBJECT, 'SetMuted', '0']]);
  });

  it('화면에서 끈 채로 진입하면 켜진 소리가 나지 않는다 — 이 결함이 #151 이다', async () => {
    window.localStorage.setItem(MUSIC_MUTED_STORAGE_KEY, 'true');
    __resetScreenAudioForTests();
    const { inst } = await renderBooted();
    expect(muteCalls(inst)).toEqual([[AUDIO_BRIDGE_OBJECT, 'SetMuted', '1']]);
  });

  it('false → true 를 전달한다', async () => {
    const { inst } = await renderBooted();
    act(() => { setMuted(true); });
    expect(muteCalls(inst).at(-1)).toEqual([AUDIO_BRIDGE_OBJECT, 'SetMuted', '1']);
  });

  it('true → false 를 전달한다', async () => {
    const { inst } = await renderBooted();
    act(() => { setMuted(true); });
    act(() => { setMuted(false); });
    expect(muteCalls(inst).at(-1)).toEqual([AUDIO_BRIDGE_OBJECT, 'SetMuted', '0']);
  });

  it('인스턴스가 서기 전에는 보내지 않는다 — 보낼 상대가 없다', async () => {
    const { inst } = await renderBooting();
    act(() => { setMuted(true); });
    expect(calls(inst)).toHaveLength(0);
  });

  it('미준비 중에 바뀐 값은 인스턴스가 선 뒤 최신값으로 전달된다', async () => {
    const { UnityHost } = await import('../../UnityHost');
    const inst = instance();
    render(<UnityHost />);
    act(() => { setMuted(true); });
    await act(async () => { boots[0].resolve(inst); });
    expect(muteCalls(inst)).toEqual([[AUDIO_BRIDGE_OBJECT, 'SetMuted', '1']]);
  });

  it('같은 값을 다시 설정해도 전달이 늘지 않는다 — 값이 바뀔 때만 보낸다', async () => {
    const { inst } = await renderBooted();
    act(() => { setMuted(true); });
    const afterChange = muteCalls(inst).length;
    act(() => { setMuted(true); });
    expect(muteCalls(inst)).toHaveLength(afterChange);
  });

  it('볼륨은 보내지 않는다 — -463 · #140 축을 이 배선이 건드리지 않는다', async () => {
    const { inst } = await renderBooted();
    act(() => { setMuted(true); });
    act(() => { setMuted(false); });
    expect(calls(inst).some((c) => c[1] === 'SetVolume')).toBe(false);
  });

  it('기존 bridge 에 회귀가 없다 — 토큰·입력 잠금 통지가 그대로 나간다', async () => {
    const { inst } = await renderBooted();
    const methods = new Set(calls(inst).map((c) => c[1] as string));
    expect(methods.has('SetMuted')).toBe(true);
    expect(methods.has('SetInputLocked')).toBe(true);
    // 비로그인 세션이라 토큰은 Clear 경로다 (authBridge.test 와 같은 전제)
    expect(methods.has('ClearAccessToken')).toBe(true);
  });
});
