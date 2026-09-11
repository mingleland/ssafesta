// @vitest-environment jsdom
// 부스 나가기 버튼 (S15P21A604-627, GitLab #174).
//
// 재는 것 둘. **부스 안에서만 뜨는가** — 밖에서 뜨면 F 로 나가던 사람까지 헷갈린다. 그리고
// **누른 뒤 FE 가 상태를 바꾸지 않는가** — 계약에 성공 콜백이 없어서, 낙관적으로 감추면 실제로
// 못 나갔을 때 버튼만 사라진 채 부스 안에 남는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { BoothExitButton } from '../../ui/BoothExitButton';
import { __resetWorldContextForTests, applyBoothContext } from '../../model/worldContext';

const sendMessage = vi.fn();
let instance: { SendMessage: typeof sendMessage } | null = { SendMessage: sendMessage };

vi.mock('../../../../unity/host/sessionManager', () => ({
  getReadyUnityInstance: () => instance,
}));

const button = () => screen.queryByRole('button', { name: /부스 나가기/ });

beforeEach(() => {
  __resetWorldContextForTests();
  sendMessage.mockReset();
  instance = { SendMessage: sendMessage };
});

afterEach(() => {
  cleanup();
  __resetWorldContextForTests();
});

describe('노출 조건', () => {
  it('부스 밖에서는 아예 없다', () => {
    render(<BoothExitButton />);
    expect(button()).toBeNull();
  });

  it('부스 안이면 뜬다', () => {
    render(<BoothExitButton />);
    act(() => applyBoothContext(true, 3));
    expect(button()).not.toBeNull();
  });

  it('나오면 사라진다 — Unity 가 보낸 false 가 내린다', () => {
    render(<BoothExitButton />);
    act(() => applyBoothContext(true, 3));
    act(() => applyBoothContext(false));
    expect(button()).toBeNull();
  });

  it('F 를 대체하지 않는다는 것을 버튼이 스스로 말한다', () => {
    render(<BoothExitButton />);
    act(() => applyBoothContext(true, 3));
    expect(screen.getByText('F')).toBeTruthy();
  });
});

describe('클릭', () => {
  it('WorldUiBridge 에 RequestExitBooth 를 한 번 보낸다', () => {
    render(<BoothExitButton />);
    act(() => applyBoothContext(true, 3));
    fireEvent.click(button()!);

    expect(sendMessage).toHaveBeenCalledTimes(1);
    expect(sendMessage).toHaveBeenCalledWith('WorldUiBridge', 'RequestExitBooth', 'exit-button');
  });

  it('누른 뒤에도 버튼은 그대로 있다 — 퇴장 확인은 Unity 의 insideBooth:false 가 한다', () => {
    render(<BoothExitButton />);
    act(() => applyBoothContext(true, 3));
    fireEvent.click(button()!);

    // 낙관적으로 감추면 실제로 못 나갔을 때 버튼만 사라진 채 부스 안에 남는다
    expect(button()).not.toBeNull();

    act(() => applyBoothContext(false));
    expect(button()).toBeNull();
  });

  it('인스턴스가 없으면 아무 일도 하지 않는다 — mock 월드·boot 전', () => {
    instance = null;
    render(<BoothExitButton />);
    act(() => applyBoothContext(true, 3));

    expect(() => fireEvent.click(button()!)).not.toThrow();
    expect(sendMessage).not.toHaveBeenCalled();
  });
});
