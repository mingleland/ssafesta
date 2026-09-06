// Toast store 정책 (S15P21A604-465) — 수명·중복 억제·상한.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  MAX_TOASTS,
  __resetToastsForTests,
  dismissToast,
  getToastsSnapshot,
  showToast,
  subscribeToasts,
} from '../../toastStore';

beforeEach(() => {
  __resetToastsForTests();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('표시와 닫기', () => {
  it('띄우면 목록에 들어간다', () => {
    showToast('저장했습니다', 'success');
    expect(getToastsSnapshot()).toHaveLength(1);
    expect(getToastsSnapshot()[0]).toMatchObject({ message: '저장했습니다', kind: 'success' });
  });

  it('닫으면 사라진다', () => {
    const id = showToast('안내');
    dismissToast(id);
    expect(getToastsSnapshot()).toHaveLength(0);
  });

  it('이미 없는 것을 닫아도 안전하다', () => {
    const id = showToast('안내');
    dismissToast(id);
    dismissToast(id);
    expect(getToastsSnapshot()).toHaveLength(0);
  });

  it('kind 기본값은 info 다', () => {
    showToast('안내');
    expect(getToastsSnapshot()[0].kind).toBe('info');
  });
});

describe('중복 억제 — 같은 알림이 쌓이면 정보가 아니라 소음이다', () => {
  it('같은 message+kind 는 하나로 유지되고 같은 id 를 돌려준다', () => {
    const first = showToast('게스트 입장에 실패했습니다.', 'error');
    const second = showToast('게스트 입장에 실패했습니다.', 'error');
    expect(second).toBe(first);
    expect(getToastsSnapshot()).toHaveLength(1);
  });

  it('kind 가 다르면 별개다', () => {
    showToast('같은 문구', 'error');
    showToast('같은 문구', 'info');
    expect(getToastsSnapshot()).toHaveLength(2);
  });
});

describe('상한 — HUD 예산 15%', () => {
  it(`${MAX_TOASTS}개를 넘으면 가장 오래된 것이 밀려난다`, () => {
    for (const n of [1, 2, 3, 4]) showToast(`알림 ${n}`, 'error');
    const messages = getToastsSnapshot().map((t) => t.message);
    expect(messages).toHaveLength(MAX_TOASTS);
    expect(messages).not.toContain('알림 1');
    expect(messages.at(-1)).toBe('알림 4');
  });
});

describe('수명', () => {
  it('info 는 4초 뒤 스스로 사라진다', () => {
    vi.useFakeTimers();
    showToast('안내', 'info');
    vi.advanceTimersByTime(4_000);
    expect(getToastsSnapshot()).toHaveLength(0);
  });

  it('success 도 4초 뒤 사라진다', () => {
    vi.useFakeTimers();
    showToast('완료', 'success');
    vi.advanceTimersByTime(4_000);
    expect(getToastsSnapshot()).toHaveLength(0);
  });

  it('error 는 스스로 사라지지 않는다 — 서버 오류를 놓치면 안 된다', () => {
    vi.useFakeTimers();
    showToast('서버 오류', 'error');
    vi.advanceTimersByTime(60_000);
    expect(getToastsSnapshot()).toHaveLength(1);
  });
});

describe('구독', () => {
  it('변화를 알리고 해제하면 더 부르지 않는다', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeToasts(listener);
    showToast('하나');
    expect(listener).toHaveBeenCalledTimes(1);

    unsubscribe();
    showToast('둘');
    expect(listener).toHaveBeenCalledTimes(1);
  });
});
