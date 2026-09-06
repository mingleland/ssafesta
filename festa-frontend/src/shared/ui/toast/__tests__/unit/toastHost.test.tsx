// @vitest-environment jsdom
// ToastHost 렌더·접근성 (S15P21A604-465).
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ToastHost } from '../../ToastHost';
import { __resetToastsForTests, getToastsSnapshot, showToast } from '../../toastStore';

beforeEach(() => {
  __resetToastsForTests();
});

afterEach(() => {
  cleanup();
});

describe('ToastHost', () => {
  it('띄운 것이 없으면 아무 것도 렌더하지 않는다', () => {
    const { container } = render(<ToastHost />);
    expect(container.querySelector('.toast-host')).toBeNull();
  });

  it('알림을 role="alert" 로 읽힌다', () => {
    const { rerender } = render(<ToastHost />);
    showToast('게스트 입장에 실패했습니다.', 'error');
    rerender(<ToastHost />);
    expect(screen.getByRole('alert')).toHaveProperty('textContent', expect.stringContaining('게스트 입장에 실패했습니다.'));
  });

  it('컨테이너는 이름 있는 region 이다', () => {
    const { rerender } = render(<ToastHost />);
    showToast('안내');
    rerender(<ToastHost />);
    expect(screen.getByRole('region', { name: '알림' })).toBeTruthy();
  });

  it('닫기 버튼으로 닫는다', () => {
    const { rerender } = render(<ToastHost />);
    showToast('안내');
    rerender(<ToastHost />);
    fireEvent.click(screen.getByRole('button', { name: '알림 닫기' }));
    expect(getToastsSnapshot()).toHaveLength(0);
  });

  it('여러 개가 쌓이면 각각 렌더한다', () => {
    const { rerender } = render(<ToastHost />);
    showToast('첫째', 'error');
    showToast('둘째', 'error');
    rerender(<ToastHost />);
    expect(screen.getAllByRole('alert')).toHaveLength(2);
  });

  it('kind 가 클래스로 드러난다 — 색만으로 구분하지 않게 하는 근거', () => {
    const { container, rerender } = render(<ToastHost />);
    showToast('오류', 'error');
    rerender(<ToastHost />);
    expect(container.querySelector('.toast-error')).not.toBeNull();
  });
});
