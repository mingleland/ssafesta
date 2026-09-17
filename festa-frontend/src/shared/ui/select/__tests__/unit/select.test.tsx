// @vitest-environment jsdom
// 선택 입력 — 네이티브 `<select>` 를 대신하므로 그 자리에서 되던 것이 그대로 돼야 한다:
// 마우스로 고르기, 키보드만으로 고르기, 그리고 상위 레이어를 끌고 닫히지 않는 ESC.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Select } from '../../Select';

const OPTIONS = [
  { value: 'ALL', label: '전체' },
  { value: 'PURCHASED', label: '구매 완료' },
  { value: 'PENDING', label: '지급 대기' },
] as const;

afterEach(cleanup);

function renderSelect(onChange = vi.fn()) {
  render(<Select aria-label="처리 상태 필터" value="ALL" options={[...OPTIONS]} onChange={onChange} />);
  return { onChange, button: screen.getByRole('button', { name: '처리 상태 필터' }) };
}

describe('Select', () => {
  it('닫혀 있을 때는 목록이 없고 고른 값만 보인다', () => {
    const { button } = renderSelect();

    expect(button.textContent).toContain('전체');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('false');
  });

  it('열어서 마우스로 고른다', () => {
    const { onChange, button } = renderSelect();

    fireEvent.click(button);
    expect(screen.getAllByRole('option')).toHaveLength(3);

    fireEvent.click(screen.getByRole('option', { name: '지급 대기' }));
    expect(onChange).toHaveBeenCalledWith('PENDING');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('키보드만으로 고른다 — 아래 화살표로 열고 이동, Enter 로 확정', () => {
    const { onChange, button } = renderSelect();

    fireEvent.keyDown(button, { key: 'ArrowDown' });
    expect(screen.getByRole('listbox')).not.toBeNull();

    fireEvent.keyDown(button, { key: 'ArrowDown' });
    fireEvent.keyDown(button, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith('PURCHASED');
  });

  it('선택 상태는 aria-selected 가 말한다', () => {
    const { button } = renderSelect();
    fireEvent.click(button);

    expect(screen.getByRole('option', { name: '전체' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('option', { name: '지급 대기' }).getAttribute('aria-selected')).toBe('false');
  });

  it('ESC 는 목록만 닫고 위로 올라가지 않는다 — 상위 dialog 가 함께 닫히면 안 된다', () => {
    const onParentEsc = vi.fn();
    const onChange = vi.fn();
    render(
      <div onKeyDown={onParentEsc}>
        <Select aria-label="상태" value="ALL" options={[...OPTIONS]} onChange={onChange} />
      </div>,
    );
    const button = screen.getByRole('button', { name: '상태' });

    fireEvent.click(button);
    fireEvent.keyDown(button, { key: 'Escape' });

    expect(screen.queryByRole('listbox')).toBeNull();
    expect(onParentEsc).not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('바깥을 누르면 닫힌다', () => {
    const { button } = renderSelect();
    fireEvent.click(button);

    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole('listbox')).toBeNull();
  });
});
