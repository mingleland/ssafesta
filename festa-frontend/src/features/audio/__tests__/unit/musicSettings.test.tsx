// @vitest-environment jsdom
// ESC 설정의 음악 항목 (S15P21A604-618).
//
// 재는 것 둘. **설정 UI 가 선호의 정본인가** — 화면 우상단 컨트롤과 같은 상태를 보고,
// 새로고침 뒤에도 남아야 한다. 그리고 **World 오디오를 건드리지 않는가** — Unity 는 아직
// SetMuted 만 받는다. 크기는 FE 가 소유한 화면 오디오에만 적용된다.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MusicSettings } from '../../ui/MusicSettings';
import {
  MUSIC_MUTED_STORAGE_KEY,
  MUSIC_VOLUME_STORAGE_KEY,
  TRACK_VOLUME,
  __resetScreenAudioForTests,
  getScreenAudioSnapshot,
  setMusicVolume,
  setMuted,
} from '../../model/screenAudio';

beforeEach(() => {
  window.localStorage.clear();
  __resetScreenAudioForTests();
});

afterEach(() => {
  cleanup();
  window.localStorage.clear();
  __resetScreenAudioForTests();
});

const slider = () => screen.getByLabelText('크기') as HTMLInputElement;

describe('음악 설정 화면', () => {
  it('기본값은 켜짐 100% 다 — 설정을 연 것만으로 소리가 바뀌지 않는다', () => {
    render(<MusicSettings />);
    expect(screen.getByRole('switch', { name: '음악' }).getAttribute('aria-checked')).toBe('true');
    expect(slider().value).toBe('100');
  });

  it('크기를 바꾸면 저장되고 다음 방문에 그대로 읽힌다', () => {
    render(<MusicSettings />);
    fireEvent.change(slider(), { target: { value: '40' } });

    expect(getScreenAudioSnapshot().volume).toBeCloseTo(0.4);
    expect(window.localStorage.getItem(MUSIC_VOLUME_STORAGE_KEY)).toBe('0.4');

    __resetScreenAudioForTests(); // 새로고침과 같은 자리에서 다시 출발한다
    expect(getScreenAudioSnapshot().volume).toBeCloseTo(0.4);
  });

  it('음소거는 화면 컨트롤과 같은 상태를 본다 — 한쪽에서 끄면 여기도 꺼져 보인다', () => {
    render(<MusicSettings />);
    act(() => setMuted(true)); // ScreenControls 가 부르는 것과 같은 함수다

    expect(screen.getByRole('switch', { name: '음악' }).getAttribute('aria-checked')).toBe('false');
    expect(window.localStorage.getItem(MUSIC_MUTED_STORAGE_KEY)).toBe('true');
  });

  it('설정에서 끈 것도 같은 키에 남는다 — 새 키를 만들지 않았다', () => {
    render(<MusicSettings />);
    fireEvent.click(screen.getByRole('switch', { name: '음악' }));

    expect(getScreenAudioSnapshot().muted).toBe(true);
    expect(window.localStorage.getItem(MUSIC_MUTED_STORAGE_KEY)).toBe('true');
  });
});

describe('저장된 값을 믿지 않는다', () => {
  it('손으로 고친 범위 밖 값은 기본값으로 떨어진다', () => {
    window.localStorage.setItem(MUSIC_VOLUME_STORAGE_KEY, 'loud');
    __resetScreenAudioForTests();
    expect(getScreenAudioSnapshot().volume).toBe(1);
  });

  it('0~1 밖으로 넣어도 그 안으로 잘린다', () => {
    setMusicVolume(3);
    expect(getScreenAudioSnapshot().volume).toBe(1);
    setMusicVolume(-1);
    expect(getScreenAudioSnapshot().volume).toBe(0);
  });
});

describe('기준선', () => {
  it('사용자 값은 TRACK_VOLUME 을 대체하지 않고 곱해진다', () => {
    // TRACK_VOLUME 은 11F 도착점에 맞춰 잡은 값이다 — 조절이 그 근거를 지우면 안 된다
    expect(TRACK_VOLUME).toBeCloseTo(0.25);
    setMusicVolume(0.5);
    expect(TRACK_VOLUME * getScreenAudioSnapshot().volume).toBeCloseTo(0.125);
  });
});
