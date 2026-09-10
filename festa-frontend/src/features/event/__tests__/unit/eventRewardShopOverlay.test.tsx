// @vitest-environment jsdom
// 이벤트 경품 상점 — 최종 shell 이 지금부터 서 있는지 (S15P21A604-599).
//
// 이 파일이 지키는 것은 하나다: **지금 화면과 상품이 들어온 뒤의 화면이 같은 구조인가.**
// `PREPARING` 에서도 격자가 살아 있고, `READY` 로 바뀌면 안내만 사라지고 같은 격자에 상품이 찬다.
// 그것이 깨지면 나중에 상점을 다시 만들게 된다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

const openVisitorOverlay = vi.fn();
vi.mock('../../../world/model/worldScreen', () => ({ openVisitorOverlay }));
// wallet 배지는 별건이다 — 여기서는 상점 구조만 본다
vi.mock('../../../wallet/ui/WalletBadge', () => ({ WalletBadge: () => <span>보유 코인</span> }));

const getRewardShopState = vi.fn();
const resolveEventSurveyTarget = vi.fn();
vi.mock('../../model/rewardShop', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../model/rewardShop')>()),
  getRewardShopState: () => getRewardShopState(),
}));
vi.mock('../../model/surveyEntry', () => ({ resolveEventSurveyTarget: () => resolveEventSurveyTarget() }));

const { EventRewardShopOverlay } = await import('../../ui/EventRewardShopOverlay');

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('PREPARING — 상품이 아직 없다', () => {
  it('빈 격자를 유지한 채 안내를 얹는다', () => {
    getRewardShopState.mockReturnValue({ phase: 'PREPARING', items: [] });
    resolveEventSurveyTarget.mockReturnValue(null);

    const { container } = render(<EventRewardShopOverlay />);

    expect(container.querySelector('.ov-card-grid')?.children.length).toBe(0);
    expect(container.querySelector('.ov-notice')).not.toBeNull();
  });

  it('안내 문구가 정확하다', () => {
    getRewardShopState.mockReturnValue({ phase: 'PREPARING', items: [] });
    resolveEventSurveyTarget.mockReturnValue(null);

    render(<EventRewardShopOverlay />);

    expect(screen.getByText('설문 참여 시 추첨을 통해 경품을 드립니다.')).toBeTruthy();
  });

  it('CTA 가 기존 Survey 오버레이로 간다 — 이벤트 설문용 새 OverlayType 을 만들지 않는다', () => {
    getRewardShopState.mockReturnValue({ phase: 'PREPARING', items: [] });
    resolveEventSurveyTarget.mockReturnValue({ kind: 'event', surveyKey: 'SSAFESTA_2026' });

    render(<EventRewardShopOverlay />);
    screen.getByRole('button', { name: '설문 참여하기' }).click();

    expect(openVisitorOverlay).toHaveBeenCalledWith('SURVEY', { kind: 'event', surveyKey: 'SSAFESTA_2026' });
  });

  it('부스가 아니라 surveyKey 로 간다 — 가짜 boothId 를 만들지 않는다 (S15P21A604-608)', () => {
    getRewardShopState.mockReturnValue({ phase: 'PREPARING', items: [] });
    resolveEventSurveyTarget.mockReturnValue({ kind: 'event', surveyKey: 'SSAFESTA_2026' });

    render(<EventRewardShopOverlay />);
    screen.getByRole('button', { name: '설문 참여하기' }).click();

    const [, payload] = openVisitorOverlay.mock.calls[0] as [string, Record<string, unknown>];
    expect(payload.kind).toBe('event');
    expect(payload).not.toHaveProperty('boothId');
  });
});

describe('READY — 상품이 들어온 뒤', () => {
  const items = [
    { id: 'r1', name: '텀블러', priceCoin: 300, remaining: 3, total: 20 },
    { id: 'r2', name: '키링', priceCoin: 120, remaining: 0, total: 50 },
  ];

  it('안내가 사라지고 같은 격자에 상품이 찬다 — shell 을 다시 만들지 않는다', () => {
    getRewardShopState.mockReturnValue({ phase: 'READY', items });
    resolveEventSurveyTarget.mockReturnValue(null);

    const { container } = render(<EventRewardShopOverlay />);

    expect(container.querySelector('.ov-notice')).toBeNull();
    expect(container.querySelector('.ov-card-grid')?.children.length).toBe(2);
    expect(screen.getByText('텀블러')).toBeTruthy();
  });

  it('카드가 필요 코인과 잔여 수량을 함께 보인다', () => {
    getRewardShopState.mockReturnValue({ phase: 'READY', items });
    resolveEventSurveyTarget.mockReturnValue(null);

    render(<EventRewardShopOverlay />);

    expect(screen.getByText('3/20 남음')).toBeTruthy();
    expect(screen.getByText('300 C')).toBeTruthy();
  });

  it('품절 상품은 교환할 수 없다', () => {
    getRewardShopState.mockReturnValue({ phase: 'READY', items });
    resolveEventSurveyTarget.mockReturnValue(null);

    const { container } = render(<EventRewardShopOverlay />);

    expect(screen.getByText('품절')).toBeTruthy();
    expect(container.querySelectorAll('.ov-card[data-disabled]').length).toBe(1);
  });
});
