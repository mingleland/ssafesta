// @vitest-environment jsdom
// 슬롯맵 배치 (2026-09-17) — 화면 배치가 실제 월드와 어긋나면 사용자가 엉뚱한 자리를 산다.
//
// 정본은 Unity `Festival.prefab` 의 `FestivalSlot_NN` 실좌표와 `FestivalMinimapArea.Normalized` 다:
//   x  01·02 = -300 … 11·12 = -875,  z  홀수 230 / 짝수 60
//   가로 u = InverseLerp(z -40…330)      → 홀수가 오른쪽
//   세로 v = 1 - InverseLerp(x -930…-240) → x 가 큰 01·02 가 아래(입구)
// 그래서 2열 × 6행이고 01 이 오른쪽 맨 아래다. 옛 표는 6열 × 2행으로 눕혀 두어 90° 어긋나 있었다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { SLOT_GRID, slotCell } from '../../../../entities/booth/slotLayout';
import { SlotMap } from '../../ui/SlotMap';
import type { SlotView } from '../../../../entities/booth/types';

afterEach(cleanup);

describe('슬롯 ↔ 월드 매핑', () => {
  it('홀수는 오른쪽 · 짝수는 왼쪽이다 — 월드 z 가 홀수 230 · 짝수 60 이다', () => {
    for (const odd of [1, 3, 5, 7, 9, 11]) expect(slotCell(odd)?.side).toBe('right');
    for (const even of [2, 4, 6, 8, 10, 12]) expect(slotCell(even)?.side).toBe('left');
  });

  it('번호가 커질수록 안쪽(위)이다 — 월드 x 가 작아진다', () => {
    expect(slotCell(1)?.row).toBe(1);
    expect(slotCell(2)?.row).toBe(1);
    expect(slotCell(11)?.row).toBe(6);
    expect(slotCell(12)?.row).toBe(6);
  });

  it('표 밖 번호는 자리를 만들지 않는다', () => {
    expect(slotCell(0)).toBeUndefined();
    expect(slotCell(13)).toBeUndefined();
  });

  it('그리는 순서가 미니맵과 같다 — 위(안쪽)에서 아래(입구)로, [왼쪽, 오른쪽]', () => {
    expect(SLOT_GRID.map((row) => [...row])).toEqual([
      [12, 11],
      [10, 9],
      [8, 7],
      [6, 5],
      [4, 3],
      [2, 1],
    ]);
  });
});

function slot(over: Partial<SlotView> & { slotId: number }): SlotView {
  return {
    slotCode: over.slotCode ?? `F11-R${String(over.slotId).padStart(2, '0')}`,
    floorNo: 11,
    type: 'USER_RENTAL',
    status: 'AVAILABLE',
    mine: false,
    boothId: null,
    boothName: null,
    leaseEndsAt: null,
    ...over,
  } as SlotView;
}

describe('슬롯맵 렌더', () => {
  const all = Array.from({ length: 12 }, (_, i) => slot({ slotId: i + 1 }));

  it('12칸을 그리고 첫 줄이 12·11, 마지막 줄이 02·01 이다', () => {
    const { container } = render(<SlotMap slots={all} selectedId={null} onSelect={vi.fn()} />);
    const rows = [...container.querySelectorAll('.slot-map-row')];
    expect(rows).toHaveLength(6);
    const codes = (row: Element) => [...row.querySelectorAll('.slot-tile-code')].map((n) => n.textContent);
    expect(codes(rows[0])).toEqual(['F11-R12', 'F11-R11']);
    expect(codes(rows[5])).toEqual(['F11-R02', 'F11-R01']);
  });

  it('출입구를 맵 안에 표시한다 — 설명문이 아니라 공간 요소다', () => {
    render(<SlotMap slots={all} selectedId={null} onSelect={vi.fn()} />);
    expect(screen.getByText('출입구')).toBeTruthy();
  });

  it('상태를 색이 아니라 글자로 말한다', () => {
    render(
      <SlotMap
        slots={[
          slot({ slotId: 1 }),
          slot({ slotId: 2, status: 'OCCUPIED' }),
          slot({ slotId: 3, type: 'ADMIN' as SlotView['type'] }),
          slot({ slotId: 4, mine: true, boothId: 7 }),
        ]}
        selectedId={null}
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByText('임대 가능')).toBeTruthy();
    expect(screen.getByText('사용 중')).toBeTruthy();
    expect(screen.getByText('운영 부스')).toBeTruthy();
    expect(screen.getAllByText('내 부스').length).toBeGreaterThan(0);
  });

  it('칸을 누르면 그 슬롯을 고른다 — 맵이 곧 선택 UI다', () => {
    const onSelect = vi.fn();
    render(<SlotMap slots={all} selectedId={null} onSelect={onSelect} />);
    fireEvent.click(screen.getByRole('button', { name: /F11-R05/ }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ slotId: 5 }));
  });

  it('선택은 윤곽만 바뀐다', () => {
    const { container } = render(<SlotMap slots={all} selectedId={5} onSelect={vi.fn()} />);
    const selected = container.querySelector('[data-selected="true"]');
    expect(selected?.querySelector('.slot-tile-code')?.textContent).toBe('F11-R05');
  });
});
