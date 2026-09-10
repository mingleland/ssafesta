// @vitest-environment jsdom
// 월드 이용 안내 — 무엇을 할 수 있는가 축이고, 조작표가 아니다 (S15P21A604-599).
//
// 두 가지를 고정한다.
//   ① `WorldHud` 조작표(WASD·F)를 복제하지 않는다 — 같은 내용이 두 곳에 있으면 하나가 낡는다
//   ② 진입은 Overlay Bus 하나다 — 그 길로 열려야 배타·ESC·입력 잠금·focus 가 따라온다
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

const openVisitorOverlay = vi.fn();
vi.mock('../../model/worldScreen', () => ({ openVisitorOverlay }));

const closeOverlay = vi.fn();
vi.mock('../../../../shared/types/overlay', () => ({ closeOverlay }));

const { WorldGuideOverlay } = await import('../../ui/WorldGuideOverlay');
const { WorldGuideLauncher } = await import('../../ui/WorldGuideLauncher');
const { hasSeenWorldGuide } = await import('../../model/worldGuide');

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('WorldGuideOverlay', () => {
  it('무엇을 할 수 있는가를 카드로 그린다', () => {
    render(<WorldGuideOverlay />);

    expect(screen.getByText('부스 둘러보기')).toBeTruthy();
    expect(screen.getByText('이벤트')).toBeTruthy();
    expect(screen.getByText('설문 참여')).toBeTruthy();
  });

  it('WorldHud 조작표를 복제하지 않는다 — 키 설명은 여기 없다', () => {
    const { container } = render(<WorldGuideOverlay />);
    const text = container.textContent ?? '';

    expect(text).not.toContain('WASD');
    expect(text).not.toContain('달리기');
    expect(text).not.toContain('점프');
  });

  it('닫기는 기존 Overlay lifecycle 을 쓴다 — 자체 상태를 두지 않는다', () => {
    render(<WorldGuideOverlay />);
    screen.getByRole('button', { name: '월드로 돌아가기' }).click();

    expect(closeOverlay).toHaveBeenCalled();
  });

  it('열리면 본 것으로 기록한다 — 닫는 방법마다 따로 기록하지 않는다', () => {
    expect(hasSeenWorldGuide()).toBe(false);
    render(<WorldGuideOverlay />);
    expect(hasSeenWorldGuide()).toBe(true);
  });
});

describe('WorldGuideLauncher', () => {
  it('처음 들어온 사람에게는 자동으로 한 번 연다', () => {
    render(<WorldGuideLauncher />);

    expect(openVisitorOverlay).toHaveBeenCalledWith('WORLD_GUIDE', {});
  });

  it('본 적이 있으면 자동으로 열지 않는다 — 월드를 가리지 않는 것이 기본값이다', () => {
    window.localStorage.setItem('festa.worldGuide.seen', 'true');

    render(<WorldGuideLauncher />);

    expect(openVisitorOverlay).not.toHaveBeenCalled();
  });

  it('언제든 다시 열 수 있다', () => {
    window.localStorage.setItem('festa.worldGuide.seen', 'true');

    render(<WorldGuideLauncher />);
    screen.getByRole('button', { name: '이용 안내' }).click();

    expect(openVisitorOverlay).toHaveBeenCalledWith('WORLD_GUIDE', {});
  });

  it('여는 길이 openVisitorOverlay 하나다 — 배타·ESC·입력 잠금이 그 길에 붙어 있다', () => {
    render(<WorldGuideLauncher />);

    // openOverlay 를 직접 부르면 clearOthers 를 건너뛰어 다른 레이어와 겹친다
    expect(openVisitorOverlay).toHaveBeenCalledTimes(1);
  });
});
