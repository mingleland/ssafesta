// @vitest-environment jsdom
// S15P21A604-554 — NumberCommitInput 자체의 배선을 확인한다. 타이핑 중에는 범위 제한이
// 없어야 하고(구현 전 버그: 최소값이 2자리 이상인 필드는 중간값이 즉시 거부돼 처음부터
// 다시 타이핑할 수 없었다), blur/Enter에서만 정수로 정리 + min~max clamp가 일어나야 한다.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NumberCommitInput } from '../../studio/ui/NumberCommitInput.tsx';

afterEach(() => {
  cleanup();
});

const setup = (value: number, min: number, max: number) => {
  const onCommit = vi.fn();
  render(<NumberCommitInput label="테스트 값" max={max} min={min} onCommit={onCommit} value={value} />);
  const input = screen.getByLabelText('테스트 값') as HTMLInputElement;
  return { input, onCommit };
};

describe('NumberCommitInput(S15P21A604-554)', () => {
  it('타이핑 중에는 최소값보다 작은 중간값도 그대로 화면에 남는다(즉시 되돌아가지 않는다)', () => {
    // 발사 간격(min=100)과 같은 상황 재현: "1", "10"을 거쳐 "100"까지 타이핑.
    const { input, onCommit } = setup(1000, 100, 10000);
    fireEvent.change(input, { target: { value: '1' } });
    expect(input.value).toBe('1');
    fireEvent.change(input, { target: { value: '10' } });
    expect(input.value).toBe('10');
    fireEvent.change(input, { target: { value: '100' } });
    expect(input.value).toBe('100');
    // 타이핑 도중엔 커밋이 전혀 일어나지 않는다(blur/Enter 전).
    expect(onCommit).not.toHaveBeenCalled();
  });

  it('숫자가 아닌 문자(글자·소수점·마이너스)는 입력에 반영되지 않는다', () => {
    const { input } = setup(5, 1, 999);
    fireEvent.change(input, { target: { value: 'a1.2-3b' } });
    expect(input.value).toBe('123');
  });

  it('blur 시 최대값을 넘는 값은 최대값으로 보정된다', () => {
    const { input, onCommit } = setup(5, 1, 999);
    fireEvent.change(input, { target: { value: '10000' } });
    fireEvent.blur(input);
    expect(onCommit).toHaveBeenCalledWith(999);
  });

  it('blur 시 최소값보다 작은 값(0 포함)은 최소값으로 보정된다', () => {
    const { input, onCommit } = setup(5, 1, 999);
    fireEvent.change(input, { target: { value: '0' } });
    fireEvent.blur(input);
    expect(onCommit).toHaveBeenCalledWith(1);
  });

  it('완전히 비운 채 blur하면 최소값으로 보정된다', () => {
    const { input, onCommit } = setup(500, 100, 10000);
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.blur(input);
    expect(onCommit).toHaveBeenCalledWith(100);
  });

  it('범위 안의 값은 blur 시 그대로 커밋된다(회귀 없음)', () => {
    const { input, onCommit } = setup(5, 1, 999);
    fireEvent.change(input, { target: { value: '42' } });
    fireEvent.blur(input);
    expect(onCommit).toHaveBeenCalledWith(42);
  });

  it('Enter를 누르면 blur 없이도 즉시 커밋된다', () => {
    const { input, onCommit } = setup(5, 1, 999);
    input.focus();
    fireEvent.change(input, { target: { value: '42' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onCommit).toHaveBeenCalledWith(42);
  });

  it('Escape를 누르면 원래 값으로 되돌아가고 커밋되지 않는다', () => {
    const { input, onCommit } = setup(5, 1, 999);
    input.focus();
    fireEvent.change(input, { target: { value: '999' } });
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(input.value).toBe('5');
    expect(onCommit).not.toHaveBeenCalled();
  });
});
