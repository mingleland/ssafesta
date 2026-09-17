// @vitest-environment jsdom
// 화면 BGM UI 배선 (S15P21A604-463) — mute 컨트롤 · Unity 생명주기 구독 · 중첩 버튼 회귀 방어.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import { ScreenControls } from '../../ui/ScreenControls';
import { ScreenAudioController } from '../../ui/ScreenAudioController';
import {
  SCREEN_AUDIO_FADE_MS,
  __resetScreenAudioForTests,
  getScreenAudioSnapshot,
  unlockAndPlay,
} from '../../model/screenAudio';
import { initUnityBridge } from '../../../../unity/bridge/events';
import { routes } from '../../../../app/router';

class FakeAudio {
  static rejectPlay = false;
  loop = false;
  preload = '';
  volume = 1;
  currentTime = 0;
  paused = true;
  src: string;
  constructor(src: string) {
    this.src = src;
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

beforeEach(() => {
  FakeAudio.rejectPlay = false;
  vi.stubGlobal('Audio', FakeAudio);
  // 제스처 뒤 상태 — 제스처 전 동작은 screenAudio.test.ts 가 잠근다
  Object.defineProperty(navigator, 'userActivation', { value: { hasBeenActive: true }, configurable: true });
  window.localStorage.clear();
  __resetScreenAudioForTests();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('ScreenControls', () => {
  it('기본은 소리 켜짐이라 aria-pressed 가 false 다', () => {
    render(<ScreenControls />);
    expect(screen.getByRole('button', { name: '배경음악 끄기' })).toHaveProperty('ariaPressed', 'false');
  });

  it('마운트하면 그 화면의 음악을 켠다 — Landing 직행에서 소리가 안 나던 회귀', async () => {
    render(<ScreenControls />);
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
  });

  it('음소거 선호가 있으면 마운트해도 켜지 않는다', async () => {
    window.localStorage.setItem('festa.settings.music.muted', 'true');
    __resetScreenAudioForTests();
    render(<ScreenControls />);
    await new Promise((r) => setTimeout(r, 10));
    expect(getScreenAudioSnapshot().playing).toBe(false);
  });

  it('자동재생이 거부되면 꺼진 것으로 표시한다 — 주소창 직행 회귀', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    FakeAudio.rejectPlay = true;
    render(<ScreenControls />);
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().pendingGesture).toBe(true));

    // 음소거가 아닌데도 들리지 않는다 — 버튼이 "켜짐" 으로 남으면 켤 방법이 사라진다
    expect(getScreenAudioSnapshot().muted).toBe(false);
    expect(screen.getByRole('button', { name: '배경음악 켜기' })).toBeTruthy();
  });

  it('거부 상태에서 누르면 음소거가 아니라 재생을 시도한다', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    FakeAudio.rejectPlay = true;
    render(<ScreenControls />);
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().pendingGesture).toBe(true));

    FakeAudio.rejectPlay = false;
    fireEvent.click(screen.getByRole('button', { name: '배경음악 켜기' }));
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
    expect(getScreenAudioSnapshot().muted).toBe(false);
  });

  it('누르면 음소거되고 라벨이 켜기로 바뀐다', () => {
    render(<ScreenControls />);
    fireEvent.click(screen.getByRole('button', { name: '배경음악 끄기' }));
    expect(getScreenAudioSnapshot().muted).toBe(true);
    expect(screen.getByRole('button', { name: '배경음악 켜기' })).toBeTruthy();
  });
});

describe('ScreenAudioController', () => {
  it('onWorldLoadStart 가 이관을 시작한다 — 로딩 씬 진입에 맞춰 꺼진다', async () => {
    initUnityBridge();
    render(<ScreenAudioController />);
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));

    vi.useFakeTimers();
    window.FestaUnity?.onWorldLoadStart?.();
    vi.advanceTimersByTime(SCREEN_AUDIO_FADE_MS * 1.5);
    expect(getScreenAudioSnapshot().playing).toBe(false);
    vi.useRealTimers();
  });

  it('onWorldGateReady 는 안전망으로 남는다 — 이미 멈췄으면 상태가 그대로다', () => {
    initUnityBridge();
    render(<ScreenAudioController />);
    window.FestaUnity?.onWorldGateReady?.();
    expect(getScreenAudioSnapshot().transitionPending).toBe(false);
    expect(getScreenAudioSnapshot().playing).toBe(false);
  });

  it('자동재생이 거부돼 대기 중이면 첫 상호작용에서 다시 켠다', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    FakeAudio.rejectPlay = true;
    render(<ScreenAudioController />);
    // /login 딥링크가 하는 것과 같은 최초 시도
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().pendingGesture).toBe(true));

    FakeAudio.rejectPlay = false;
    fireEvent.pointerDown(window);
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));
  });

  it('구독은 unmount 에서 해제된다', async () => {
    initUnityBridge();
    const view = render(<ScreenAudioController />);
    unlockAndPlay();
    await vi.waitFor(() => expect(getScreenAudioSnapshot().playing).toBe(true));

    view.unmount();
    vi.useFakeTimers();
    window.FestaUnity?.onWorldLoadStart?.();
    vi.advanceTimersByTime(SCREEN_AUDIO_FADE_MS * 1.5);
    // 구독이 남아 있었다면 여기서 멈췄을 것이다
    expect(getScreenAudioSnapshot().playing).toBe(true);
    vi.useRealTimers();
  });
});

describe('Landing 배치', () => {
  it('컨트롤은 전체화면 <button> 의 형제다 — 안에 있으면 중첩 버튼이라 무효 마크업이다', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <QueryClientProvider client={client}>
        <RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/'] })} />
      </QueryClientProvider>,
    );
    const landingRoot = container.querySelector('.landing-root');
    const controls = container.querySelector('.screen-controls');
    expect(landingRoot).not.toBeNull();
    expect(controls).not.toBeNull();
    expect(landingRoot?.contains(controls as Node)).toBe(false);
  });
});
