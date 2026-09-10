// @vitest-environment jsdom
// OverlayCardGrid — 가이드 카드와 상점 상품 카드가 공유하는 하나의 어휘 (S15P21A604-599).
//
// 핵심은 **빈 목록에서도 격자를 걷지 않는 것**이다. 상점이 준비 중일 때 shell·grid 를 유지한 채
// 그 위에 안내를 얹는 구조라, 여기서 목록이 사라지면 상품이 도착했을 때 화면을 다시 만들게 된다.
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { OverlayCardGrid } from '../../ui/OverlayCardGrid';

afterEach(cleanup);

describe('OverlayCardGrid', () => {
  it('카드를 제목·설명과 함께 그린다', () => {
    render(
      <OverlayCardGrid
        label="목록"
        cards={[{ id: 'a', title: '부스 둘러보기', desc: '전시를 본다' }]}
      />,
    );

    expect(screen.getByText('부스 둘러보기')).toBeTruthy();
    expect(screen.getByText('전시를 본다')).toBeTruthy();
  });

  it('빈 목록에서도 격자를 유지한다 — 그 위에 안내를 얹기 위해서다', () => {
    const { container } = render(<OverlayCardGrid label="경품 목록" cards={[]} />);

    const grid = container.querySelector('.ov-card-grid');
    expect(grid).not.toBeNull();
    expect(grid?.children.length).toBe(0);
    // 이름은 남는다 — 스크린 리더가 "빈 목록" 을 말할 수 있어야 한다
    expect(screen.getByLabelText('경품 목록')).toBeTruthy();
  });

  it('chips·action 이 있으면 카드 하단에 함께 놓는다', () => {
    render(
      <OverlayCardGrid
        label="경품 목록"
        cards={[{
          id: 'r1',
          title: '텀블러',
          chips: <span className="ov-chip">3/20 남음</span>,
          action: <button type="button">교환</button>,
        }]}
      />,
    );

    expect(screen.getByText('3/20 남음')).toBeTruthy();
    expect(screen.getByRole('button', { name: '교환' })).toBeTruthy();
  });

  it('disabled 카드를 표시로 구분한다 — 품절이 스타일로만 갈리지 않게', () => {
    const { container } = render(
      <OverlayCardGrid label="경품 목록" cards={[{ id: 'r1', title: '품절 상품', disabled: true }]} />,
    );

    expect(container.querySelector('.ov-card[data-disabled]')).not.toBeNull();
  });
});
