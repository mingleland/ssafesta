// @vitest-environment jsdom
// World HUD 버튼은 마우스로 눌러도 canvas focus 를 뺏지 않는다 (S15P21A604-648).
//
// 브라우저는 mousedown 에서 그 버튼으로 focus 를 옮긴다. HUD 버튼이 그러면 canvas 가 focus 를 잃어
// WASD·F 가 Unity 에 안 들어간다(!279). 나가기 버튼은 눌린 뒤 사라져 body 로 떨어지고, 이용 안내
// 버튼은 -428 이 그 버튼으로 focus 를 돌려준다 — 둘 다 캔버스를 다시 클릭하기 전까지 키가 죽는다
// (2026-09-11 5173 실측). 여기서 잠그는 것은 **HUD 안 모든 버튼의 mousedown 이 기본 동작을 막는가**
// 와 **click 은 그대로 오는가** 둘이다. jsdom 은 mousedown → focus 이동을 흉내 내지 않으므로
// defaultPrevented 로 판정한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { __resetWorldContextForTests, applyBoothContext } from '../../model/worldContext';
import { __resetHostPhaseForTests } from '../../../../unity/host/hostPhase';

vi.mock('../../../../unity/host/sessionManager', () => ({ getReadyUnityInstance: () => ({ SendMessage: vi.fn() }) }));
vi.mock('../../ui/ConsultationQuickAccess', () => ({
  ConsultationQuickAccess: () => <button type="button" className="cqa-btn" aria-label="상담" />,
}));

const { WorldHud } = await import('../../ui/WorldHud');

beforeEach(() => { __resetWorldContextForTests(); __resetHostPhaseForTests(); });
afterEach(() => { cleanup(); __resetWorldContextForTests(); });

function mousedownPrevented(el: Element): boolean {
  // fireEvent 는 "기본 동작이 막히지 않았으면 true" 를 돌려준다 (dispatchEvent 규약)
  return fireEvent.mouseDown(el) === false;
}

describe('HUD 버튼 mousedown', () => {
  it('HUD 안의 모든 버튼이 기본 동작(focus 이동)을 막는다', () => {
    render(<WorldHud />);
    act(() => applyBoothContext(true, 6)); // 나가기 버튼까지 띄운다
    const buttons = document.querySelectorAll('.world-hud button');
    expect(buttons.length).toBeGreaterThanOrEqual(4); // 접기 · 상담 · 이용 안내 · 나가기
    for (const b of buttons) expect(mousedownPrevented(b)).toBe(true);
  });

  it('click 은 그대로 온다 — 접기 버튼을 누르면 카드가 접힌다', () => {
    render(<WorldHud />);
    const close = screen.getByLabelText('조작 안내 닫기');
    fireEvent.mouseDown(close);
    fireEvent.click(close);
    expect(screen.queryByLabelText('조작 안내')).toBeNull();
    expect(screen.getByRole('button', { name: '조작 안내' })).toBeTruthy();
  });

  it('나가기 버튼도 click 이 그대로 온다 — 퇴장 요청이 나간다', () => {
    render(<WorldHud />);
    act(() => applyBoothContext(true, 6));
    const exit = screen.getByRole('button', { name: /부스 나가기/ });
    fireEvent.mouseDown(exit);
    expect(() => fireEvent.click(exit)).not.toThrow();
  });

  it('canvas 가 focus 를 쥔 채로 HUD 버튼을 눌러도 focus 가 남는다', () => {
    const canvas = document.createElement('canvas');
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    canvas.focus();
    expect(document.activeElement).toBe(canvas);

    render(<WorldHud />);
    const close = screen.getByLabelText('조작 안내 닫기');
    fireEvent.mouseDown(close);
    fireEvent.click(close);
    // mousedown 기본 동작을 막았으므로 focus 이동이 일어나지 않는다
    expect(document.activeElement).toBe(canvas);
    canvas.remove();
  });
});
