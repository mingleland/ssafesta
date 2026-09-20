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
  MUSIC_VOLUME_STORAGE_KEY,
  __resetScreenAudioForTests,
  setMuted,
  setMusicVolume,
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
const volumeCalls = (i: UnityInstance) => calls(i).filter((c) => c[1] === 'SetVolume');

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

  // 2026-09-14 (S15P21A604-733) 계약 변경. 여기 있던 '볼륨은 보내지 않는다' 는 폐기했다 —
  // Unity AudioBridge.SetVolume 이 이미 있었고 FE 호출만 빠져 있었다.
  it('mute 를 바꿔도 SetVolume 이 덩달아 늘지 않는다 — 두 채널은 독립이다', async () => {
    const { inst } = await renderBooted();
    const before = volumeCalls(inst).length;
    act(() => { setMuted(true); });
    act(() => { setMuted(false); });
    expect(volumeCalls(inst)).toHaveLength(before);
  });
});

describe('볼륨 승계 배선 (-733)', () => {
  it('인스턴스가 서면 현재 볼륨을 먼저 알린다 — 기본값은 1 이다', async () => {
    const { inst } = await renderBooted();
    expect(volumeCalls(inst)).toEqual([[AUDIO_BRIDGE_OBJECT, 'SetVolume', '1']]);
  });

  it('화면에서 줄인 채로 진입하면 그 값이 넘어간다', async () => {
    window.localStorage.setItem(MUSIC_VOLUME_STORAGE_KEY, '0.4');
    __resetScreenAudioForTests();
    const { inst } = await renderBooted();
    expect(volumeCalls(inst)).toEqual([[AUDIO_BRIDGE_OBJECT, 'SetVolume', '0.4']]);
  });

  it('0~1 을 그대로 보낸다 — Unity 가 Mathf.Clamp01 로 받는다', async () => {
    const { inst } = await renderBooted();
    act(() => { setMusicVolume(0.25); });
    expect(volumeCalls(inst).at(-1)).toEqual([AUDIO_BRIDGE_OBJECT, 'SetVolume', '0.25']);
  });

  it('미준비 중에 바뀐 값은 인스턴스가 선 뒤 최신값 하나로만 전달된다', async () => {
    const { UnityHost } = await import('../../UnityHost');
    const inst = instance();
    render(<UnityHost />);
    act(() => { setMusicVolume(0.2); });
    act(() => { setMusicVolume(0.6); });
    await act(async () => { boots[0].resolve(inst); });
    expect(volumeCalls(inst)).toEqual([[AUDIO_BRIDGE_OBJECT, 'SetVolume', '0.6']]);
  });

  it('인스턴스가 서기 전에는 보내지 않는다', async () => {
    const { inst } = await renderBooting();
    act(() => { setMusicVolume(0.5); });
    expect(calls(inst)).toHaveLength(0);
  });

  it('음소거 중에 바꾼 값도 그대로 보낸다 — 복원은 Unity Apply() 몫이라 FE 가 막지 않는다', async () => {
    const { inst } = await renderBooted();
    act(() => { setMuted(true); });
    act(() => { setMusicVolume(0.3); });
    expect(volumeCalls(inst).at(-1)).toEqual([AUDIO_BRIDGE_OBJECT, 'SetVolume', '0.3']);
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
