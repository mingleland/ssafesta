// @vitest-environment jsdom
// 월드 이용 안내 — 첫 진입 환영 화면이다 (S15P21A604-599).
//
// 두 가지를 고정한다.
//   ① 조작은 **핵심 4키 요약**까지만 — 달리기·점프 같은 확장 키는 ESC 메뉴 조작 안내 소관이다
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
  it('첫 방문 동선을 3단계로 안내한다', () => {
    render(<WorldGuideOverlay />);

    expect(screen.getByText('부스를 둘러보세요')).toBeTruthy();
    expect(screen.getByText('다양한 콘텐츠에 참여하세요')).toBeTruthy();
    expect(screen.getByText('도움이 필요하면 상담을 이용하세요')).toBeTruthy();
  });

  it('조작은 핵심 4키만 요약한다 — 확장 키는 ESC 조작 안내 소관이다', () => {
    const { container } = render(<WorldGuideOverlay />);
    const text = container.textContent ?? '';

    expect(text).toContain('이동');
    expect(text).toContain('상호작용');
    expect(text).toContain('채팅');
    expect(text).toContain('메뉴');
    expect(text).not.toContain('달리기');
    expect(text).not.toContain('점프');
    expect(text).not.toContain('미니맵');
  });

  it('닫기는 기존 Overlay lifecycle 을 쓴다 — 자체 상태를 두지 않는다', () => {
    render(<WorldGuideOverlay />);
    screen.getByRole('button', { name: '축제 둘러보기' }).click();

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
