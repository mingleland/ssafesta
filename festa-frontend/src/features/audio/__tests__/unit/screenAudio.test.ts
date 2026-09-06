// @vitest-environment jsdom
// 화면 BGM 모델 (S15P21A604-463) — 재생 unlock · 자동재생 거부 · mute 지속 · 11F 이관 규칙.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  MUSIC_MUTED_STORAGE_KEY,
  SCREEN_AUDIO_FADE_MS,
  TRACK_VOLUME,
  __resetScreenAudioForTests,
  beginWorldTransition,
  getScreenAudioSnapshot,
  handOffToWorld,
  enterScreen,
  setMuted,
  unlockAndPlay,
} from '../../model/screenAudio';

// jsdom 은 HTMLMediaElement 재생을 구현하지 않는다 — 계약(play/pause/volume/loop)만 흉내 낸다.
class FakeAudio {
  static instances: FakeAudio[] = [];
  static rejectPlay = false;
  loop = false;
  preload = '';
  volume = 1;
  currentTime = 0;
  paused = true;
  constructor(public src: string) {
    FakeAudio.instances.push(this);
  }
  play(): Promise<void> {
    if (FakeAudio.rejectPlay) return Promise.reject(new DOMException('blocked', 'NotAllowedError'));
    this.paused = false;
    return Promise.resolve();
  }
  pause(): void {
    this.paused = true;
  }
}

function currentAudio(): FakeAudio {
  const audio = FakeAudio.instances.at(-1);
  if (audio === undefined) throw new Error('Audio 가 만들어지지 않았다');
  return audio;
}

beforeEach(() => {
  FakeAudio.instances = [];
  FakeAudio.rejectPlay = false;
  vi.stubGlobal('Audio', FakeAudio);
  window.localStorage.clear();
  __resetScreenAudioForTests();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('재생 시작', () => {
  it('unlockAndPlay 는 element 를 만들고 loop 로 재생한다', async () => {
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
    expect(currentAudio().loop).toBe(true);
    expect(currentAudio().paused).toBe(false);
    // 11F 도착점(-36.8 dBFS)에 맞춰 실측으로 정한 값 — 임의로 올리면 전환에서 소리가 뚝 떨어진다
    expect(currentAudio().volume).toBe(TRACK_VOLUME);
  });

  it('element 는 첫 재생 시도 전까지 만들어지지 않는다 — 4.5MB 를 미리 받지 않는다', () => {
    expect(FakeAudio.instances).toHaveLength(0);
  });

  it('이미 울리고 있으면 다시 만들지 않는다 (Landing 을 거쳐 온 Login 진입)', async () => {
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
    unlockAndPlay();
    expect(FakeAudio.instances).toHaveLength(1);
  });
});

describe('자동재생 거부', () => {
  it('실패를 삼키지 않고 pendingGesture 로 드러낸다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    FakeAudio.rejectPlay = true;
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().pendingGesture).toBe(true));
    expect(getScreenAudioSnapshot().playing).toBe(false);
    expect(warn).toHaveBeenCalled();
  });

  it('다음 시도가 성공하면 pendingGesture 가 풀린다', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    FakeAudio.rejectPlay = true;
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().pendingGesture).toBe(true));

    FakeAudio.rejectPlay = false;
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
    expect(getScreenAudioSnapshot().pendingGesture).toBe(false);
  });
});

describe('mute 선호', () => {
  it('끄면 멈추고 localStorage 에 남는다', async () => {
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));

    setMuted(true);
    expect(currentAudio().paused).toBe(true);
    expect(getScreenAudioSnapshot().playing).toBe(false);
    expect(window.localStorage.getItem(MUSIC_MUTED_STORAGE_KEY)).toBe('true');
  });

  it('음소거 상태에서는 unlockAndPlay 가 아무 것도 하지 않는다', () => {
    setMuted(true);
    unlockAndPlay();
    expect(getScreenAudioSnapshot().playing).toBe(false);
  });

  it('해제하면 그 자리에서 다시 켜진다 — 해제 클릭 자체가 제스처다', async () => {
    setMuted(true);
    setMuted(false);
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
    expect(window.localStorage.getItem(MUSIC_MUTED_STORAGE_KEY)).toBe('false');
  });
});

describe('World 이관', () => {
  it('onWorldLoadStart 에서 바로 줄기 시작한다 — 로딩 씬 위로 계속 울리지 않는다', async () => {
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
    vi.useFakeTimers();

    beginWorldTransition();
    expect(getScreenAudioSnapshot().transitionPending).toBe(true);

    // 즉시 끊지는 않는다 — 줄어드는 중이다
    vi.advanceTimersByTime(SCREEN_AUDIO_FADE_MS / 2);
    expect(currentAudio().paused).toBe(false);
    expect(currentAudio().volume).toBeLessThan(TRACK_VOLUME);

    vi.advanceTimersByTime(SCREEN_AUDIO_FADE_MS);
    expect(currentAudio().paused).toBe(true);
    expect(getScreenAudioSnapshot().playing).toBe(false);
    expect(getScreenAudioSnapshot().transitionPending).toBe(false);
  });

  it('onWorldGateReady 는 안전망이다 — loadStart 를 못 받아도 이관된다', async () => {
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
    vi.useFakeTimers();

    handOffToWorld();
    vi.advanceTimersByTime(SCREEN_AUDIO_FADE_MS * 1.5);
    expect(currentAudio().paused).toBe(true);
    expect(getScreenAudioSnapshot().playing).toBe(false);
  });

  it('이관 중에는 재생이 다시 시작되지 않는다', () => {
    beginWorldTransition();
    unlockAndPlay();
    expect(getScreenAudioSnapshot().playing).toBe(false);
  });

  it('울리지 않는 상태에서 이관 신호가 와도 안전하다', () => {
    beginWorldTransition();
    handOffToWorld();
    expect(getScreenAudioSnapshot().transitionPending).toBe(false);
  });
});

describe('이관 뒤 되살아나지 않는다 (엘리베이터 재생 버그)', () => {
  it('이관이 끝나면 unlockAndPlay 가 다시 켜지 않는다', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));

    vi.useFakeTimers();
    beginWorldTransition();
    vi.advanceTimersByTime(SCREEN_AUDIO_FADE_MS * 1.5);
    expect(getScreenAudioSnapshot().playing).toBe(false);
    vi.useRealTimers();

    // 월드 안에서의 클릭·키 입력이 이 경로로 들어온다 — 화면 음악이 되살아나면 안 된다
    unlockAndPlay();
    await new Promise((r) => setTimeout(r, 10));
    expect(getScreenAudioSnapshot().playing).toBe(false);
    expect(currentAudio().paused).toBe(true);
  });

  it('화면으로 돌아오면 다시 켤 수 있다', async () => {
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
    vi.useFakeTimers();
    beginWorldTransition();
    vi.advanceTimersByTime(SCREEN_AUDIO_FADE_MS * 1.5);
    vi.useRealTimers();

    enterScreen();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
  });
});
