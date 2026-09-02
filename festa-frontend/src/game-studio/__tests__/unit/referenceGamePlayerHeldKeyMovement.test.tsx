// @vitest-environment jsdom
// S15P21A604-363 — 방향키를 keydown마다 즉시 처리하던 것을, 처음 눌린 순간에만 즉시
// 한 걸음(탭 반응성) + 계속 누르고 있는 동안은 120ms tick마다 한 걸음(균일한 속도)으로 바꿨다.
// 이 파일은 컴포넌트 레벨에서 그 타이밍 계약 자체를 고정한다 — referenceRuntime 단의
// movePlayerFromHeldKeys 자체 로직은 movePlayerFromHeldKeys.test.ts에서 이미 검증했으므로,
// 여기서는 "keydown/keyup/setInterval이 실제로 그 함수를 올바른 타이밍에 호출하는가"만 본다.
import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

beforeEach(() => {
  vi.useFakeTimers();
});

const STABLE_ASSET_URLS = {};

// library Scene은 16×10, 스폰은 (8,8) — grp-player의 left%는 ((x+0.5)/16)*100으로 계산된다.
const leftPercentFor = (x: number) => `${((x + 0.5) / 16) * 100}%`;

const setup = () => {
  const project = createStarterProject(90);
  const { container } = render(
    <ReferenceGamePlayer
      assetUrls={STABLE_ASSET_URLS}
      mode="PREVIEW"
      onExit={() => undefined}
      project={project}
      sessionPort={createPreviewGameSessionPort()}
    />,
  );
  const player = () => container.querySelector('.grp-player') as HTMLElement;
  return { player };
};

describe('ReferenceGamePlayer — 방향키 입력 타이밍', () => {
  it('키를 한 번 눌렀다 떼면(탭) 정확히 한 칸만 즉시 이동한다', () => {
    const { player } = setup();
    expect(player().style.left).toBe(leftPercentFor(8));

    act(() => { fireEvent.keyDown(window, { key: 'ArrowRight' }); });
    expect(player().style.left).toBe(leftPercentFor(9));

    act(() => { fireEvent.keyUp(window, { key: 'ArrowRight' }); });
    // 뗀 뒤로는 tick이 지나도 더 움직이지 않는다.
    act(() => { vi.advanceTimersByTime(240); });
    expect(player().style.left).toBe(leftPercentFor(9));
  });

  it('키를 누른 채로 있으면(auto-repeat 없이) 120ms tick마다 정확히 한 칸씩 균일하게 이동한다', () => {
    const { player } = setup();

    act(() => { fireEvent.keyDown(window, { key: 'ArrowRight' }); });
    expect(player().style.left).toBe(leftPercentFor(9)); // 처음 눌린 순간의 즉시 한 걸음

    act(() => { vi.advanceTimersByTime(120); });
    expect(player().style.left).toBe(leftPercentFor(10)); // 1틱 후 정확히 한 칸 더

    act(() => { vi.advanceTimersByTime(120); });
    expect(player().style.left).toBe(leftPercentFor(11)); // 다음 틱도 정확히 한 칸

    act(() => { fireEvent.keyUp(window, { key: 'ArrowRight' }); });
    act(() => { vi.advanceTimersByTime(360); });
    expect(player().style.left).toBe(leftPercentFor(11)); // 뗀 뒤로는 정지
  });

  it('OS auto-repeat으로 같은 키의 keydown이 반복돼도(뗀 적 없음) 추가로 즉시 이동하지 않는다', () => {
    // jsdom은 실제 auto-repeat을 만들지 않지만, "이미 눌려 있는 키의 keydown 재발"을
    // repeat=true keydown 재전송으로 흉내 낸다 — 이게 급발진의 실제 원인이었다.
    const { player } = setup();

    act(() => { fireEvent.keyDown(window, { key: 'ArrowRight', repeat: false }); });
    expect(player().style.left).toBe(leftPercentFor(9));

    act(() => {
      fireEvent.keyDown(window, { key: 'ArrowRight', repeat: true });
      fireEvent.keyDown(window, { key: 'ArrowRight', repeat: true });
      fireEvent.keyDown(window, { key: 'ArrowRight', repeat: true });
    });
    // pressedDirectionsRef에 이미 있으므로 세 번의 재발 keydown은 전부 무시된다.
    expect(player().style.left).toBe(leftPercentFor(9));
  });

  it('두 방향키를 동시에 누르고 있으면 매 tick 대각선으로 한 칸씩 이동한다', () => {
    const { player } = setup();

    act(() => {
      fireEvent.keyDown(window, { key: 'ArrowUp' });
      fireEvent.keyDown(window, { key: 'ArrowRight' });
    });
    expect(player().style.left).toBe(leftPercentFor(9)); // 즉시: 대각선 한 걸음(위+오른쪽)

    act(() => { vi.advanceTimersByTime(120); });
    expect(player().style.left).toBe(leftPercentFor(10)); // tick마다 계속 대각선으로 전진
  });

  it('창이 포커스를 잃으면(blur) 눌린 키가 정리되어 다음 tick에 더 이상 움직이지 않는다', () => {
    const { player } = setup();

    act(() => { fireEvent.keyDown(window, { key: 'ArrowRight' }); });
    expect(player().style.left).toBe(leftPercentFor(9));

    act(() => { fireEvent(window, new Event('blur')); });
    act(() => { vi.advanceTimersByTime(240); });
    // keyup 없이 포커스만 잃었지만, blur가 pressedDirectionsRef를 비워 더 전진하지 않는다.
    expect(player().style.left).toBe(leftPercentFor(9));
  });
});
