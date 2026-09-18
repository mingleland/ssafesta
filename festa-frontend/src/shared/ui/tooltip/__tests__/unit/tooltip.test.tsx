// @vitest-environment jsdom
// 공통 툴팁 (2026-09-17) — `title` 을 대체하면서 title 이 못 하던 것을 하는지 잠근다.
//
// jsdom 은 레이아웃을 재지 않아 좌표가 전부 0 이다. 그래서 **위치 계산은 순수 함수로 따로 검산**하고,
// 렌더 테스트는 언제 뜨고 언제 사라지는지와 접근성 배선만 본다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { Tooltip, computeTooltipPosition } from '../../Tooltip';

const DELAY = 120;
const settle = () => act(() => { vi.advanceTimersByTime(DELAY); });

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe('표시·소멸', () => {
  const trigger = () => screen.getByRole('button', { name: '전체화면' });
  const tip = () => screen.queryByRole('tooltip');

  function renderOne(props: Partial<Parameters<typeof Tooltip>[0]> = {}) {
    return render(
      <Tooltip content="전체화면" {...props}>
        <button type="button" aria-label="전체화면" />
      </Tooltip>,
    );
  }

  it('hover 하면 지연 뒤에 뜬다 — 스치는 커서에는 따라붙지 않는다', () => {
    renderOne();
    fireEvent.mouseEnter(trigger());
    expect(tip()).toBeNull();
    settle();
    expect(tip()?.textContent).toBe('전체화면');
  });

  it('지연 안에 떠나면 뜨지 않는다', () => {
    renderOne();
    fireEvent.mouseEnter(trigger());
    fireEvent.mouseLeave(trigger());
    settle();
    expect(tip()).toBeNull();
  });

  it('키보드 focus 에서도 뜬다 — title 이 못 하던 것이다', () => {
    renderOne();
    fireEvent.focus(trigger());
    settle();
    expect(tip()).not.toBeNull();
    fireEvent.blur(trigger());
    expect(tip()).toBeNull();
  });

  it('ESC 로 닫는다', () => {
    renderOne();
    fireEvent.focus(trigger());
    settle();
    act(() => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); });
    expect(tip()).toBeNull();
  });

  it('누르면 닫힌다 — 클릭으로 열린 화면을 가리지 않는다', () => {
    const onClick = vi.fn();
    render(
      <Tooltip content="전체화면">
        <button type="button" aria-label="전체화면" onClick={onClick} />
      </Tooltip>,
    );
    fireEvent.mouseEnter(trigger());
    settle();
    fireEvent.pointerDown(trigger());
    expect(tip()).toBeNull();
    fireEvent.click(trigger());
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('트리거의 기존 핸들러를 덮어쓰지 않는다', () => {
    const onMouseEnter = vi.fn();
    render(
      <Tooltip content="전체화면">
        <button type="button" aria-label="전체화면" onMouseEnter={onMouseEnter} />
      </Tooltip>,
    );
    fireEvent.mouseEnter(trigger());
    expect(onMouseEnter).toHaveBeenCalledTimes(1);
  });

  it('aria-describedby 로 트리거와 말풍선을 잇는다', () => {
    renderOne();
    expect(trigger().getAttribute('aria-describedby')).toBeNull();
    fireEvent.focus(trigger());
    settle();
    expect(trigger().getAttribute('aria-describedby')).toBe(tip()?.id);
  });

  it('disabled 면 툴팁만 꺼진다', () => {
    renderOne({ disabled: true });
    fireEvent.mouseEnter(trigger());
    settle();
    expect(tip()).toBeNull();
  });

  it('content 가 비면 달지 않는다 — 조건부 설명이 빈 말풍선을 만들면 안 된다', () => {
    renderOne({ content: null });
    fireEvent.mouseEnter(trigger());
    settle();
    expect(tip()).toBeNull();
  });

  it('비활성 트리거는 앵커가 hover 를 받는다 — 브라우저가 비활성 컨트롤에 마우스 이벤트를 주지 않는다', () => {
    const { container } = render(
      <Tooltip content="이미 진행 중인 상담이 있습니다">
        <button type="button" aria-label="상담" disabled />
      </Tooltip>,
    );
    const anchor = container.querySelector('.festa-tooltip-anchor');
    expect(anchor).not.toBeNull();
    fireEvent.mouseEnter(anchor as Element);
    settle();
    expect(tip()?.textContent).toBe('이미 진행 중인 상담이 있습니다');
  });

  it('활성 트리거에는 앵커를 만들지 않는다 — flex·grid 배치가 틀어진다', () => {
    const { container } = renderOne();
    expect(container.querySelector('.festa-tooltip-anchor')).toBeNull();
  });
});

describe('위치 보정', () => {
  const view = { width: 1000, height: 800 };
  const bubble = { width: 120, height: 32 };
  const anchorAt = (left: number, top: number, width = 40, height = 40) => ({
    left, top, right: left + width, bottom: top + height, width, height,
  });

  it('기본은 트리거 위 중앙이다', () => {
    const pos = computeTooltipPosition(anchorAt(500, 400), bubble, 'top', view);
    expect(pos.top).toBe(400 - 32 - 8);
    expect(pos.left).toBe(500 + 20 - 60);
  });

  it('위가 모자라면 아래로 뒤집는다', () => {
    const pos = computeTooltipPosition(anchorAt(500, 4), bubble, 'top', view);
    expect(pos.top).toBe(4 + 40 + 8);
  });

  it('아래가 모자라면 위로 뒤집는다', () => {
    const pos = computeTooltipPosition(anchorAt(500, 760), bubble, 'bottom', view);
    expect(pos.top).toBe(760 - 32 - 8);
  });

  it('좌우 가장자리에서 잘리지 않는다', () => {
    expect(computeTooltipPosition(anchorAt(0, 400), bubble, 'top', view).left).toBe(8);
    expect(computeTooltipPosition(anchorAt(980, 400), bubble, 'top', view).left).toBe(1000 - 120 - 8);
  });

  it('left 로 지정해도 왼쪽이 모자라면 오른쪽으로 간다', () => {
    const pos = computeTooltipPosition(anchorAt(10, 400), bubble, 'left', view);
    expect(pos.left).toBe(10 + 40 + 8);
  });

  it('말풍선이 뷰포트보다 커도 가장자리 여백을 지킨다', () => {
    const huge = { width: 1200, height: 900 };
    const pos = computeTooltipPosition(anchorAt(500, 400), huge, 'top', view);
    expect(pos.left).toBe(8);
    expect(pos.top).toBe(8);
  });
});
