// @vitest-environment jsdom
// OverlayNotice — 본문을 대체하지 않고 그 위에 얹히는 안내 (S15P21A604-599).
//
// `OverlayEmpty` 와 갈라 두는 이유를 여기서 고정한다: 그쪽은 뒤에 아무것도 없을 때, 이쪽은 뒤에
// 유지할 것(상점 격자)이 있을 때다. 안내가 사라지면 뒤가 그대로 드러나야 한다.
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { OverlayCardGrid } from '../../ui/OverlayCardGrid';
import { OverlayNotice } from '../../ui/OverlayNotice';

afterEach(cleanup);

describe('OverlayNotice', () => {
  it('제목·본문·액션을 그린다', () => {
    render(
      <OverlayNotice
        title="경품 상점 준비 중"
        message="설문 참여 시 추첨을 통해 경품을 드립니다."
        action={<button type="button">설문 참여하기</button>}
      />,
    );

    expect(screen.getByText('경품 상점 준비 중')).toBeTruthy();
    expect(screen.getByText('설문 참여 시 추첨을 통해 경품을 드립니다.')).toBeTruthy();
    expect(screen.getByRole('button', { name: '설문 참여하기' })).toBeTruthy();
  });

  it('안내가 떠 있어도 뒤의 격자가 남는다 — 상품이 오면 안내만 사라진다', () => {
    const { container } = render(
      <div className="ov-grid-wrap">
        <OverlayCardGrid label="경품 목록" cards={[]} />
        <OverlayNotice title="준비 중" />
      </div>,
    );

    expect(container.querySelector('.ov-grid-wrap > .ov-card-grid')).not.toBeNull();
    expect(container.querySelector('.ov-grid-wrap > .ov-notice')).not.toBeNull();
  });

  it('액션을 쓸 수 없는 사유를 hint 로 남긴다 — 눌러서 실패하게 두지 않는다', () => {
    render(<OverlayNotice title="준비 중" hint="설문 위치가 정해지면 참여할 수 있습니다." />);

    expect(screen.getByText('설문 위치가 정해지면 참여할 수 있습니다.')).toBeTruthy();
  });

  it('상태 변화를 알리는 역할이므로 role=status 다', () => {
    render(<OverlayNotice title="준비 중" />);
    expect(screen.getByRole('status')).toBeTruthy();
  });
});
