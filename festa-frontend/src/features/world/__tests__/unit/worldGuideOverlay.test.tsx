// @vitest-environment jsdom
// 월드 이용 안내 — 월드 진입 환영 화면이다 (S15P21A604-599).
//
// 두 가지를 고정한다.
//   ① 조작은 **핵심 4키 요약**까지만 — 달리기·점프 같은 확장 키는 ESC 메뉴 조작 안내 소관이다
//   ② 진입은 Overlay Bus 하나다 — 그 길로 열려야 배타·ESC·입력 잠금·focus 가 따라온다
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

const openVisitorOverlay = vi.fn();
vi.mock('../../model/worldScreen', () => ({ openVisitorOverlay }));

const closeOverlay = vi.fn();
vi.mock('../../../../shared/types/overlay', () => ({ closeOverlay }));

const { WorldGuideOverlay } = await import('../../ui/WorldGuideOverlay');

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
});
