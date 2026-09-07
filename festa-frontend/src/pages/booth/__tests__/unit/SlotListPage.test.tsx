// @vitest-environment jsdom
// -87 완료 조건 3종(슬롯 상태 3종 구분·leaseEndsAt 만료 임박 표시·로딩/오류 상태)의 회귀 방어.
// 실서버(local BE)로는 AVAILABLE 12칸만 재현된다 — OCCUPIED·mine 은 회원 임대가 있어야 생기고
// 그 회원 세션이 D5(OAuth 자격증명 6종) 부재로 막혀 있다. 오류 분기도 실서버로 못 만든다:
// 세션이 메모리 전용이라 API 가 죽으면 로그인 자체가 되지 않아 페이지에 도달할 수 없다.
// 그래서 실서버가 덮지 못하는 세 상태를 여기서 고정한다(G-4 환경 도입 후 첫 페이지 테스트).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SlotView } from '../../../../entities/booth/types';

const getSlots = vi.fn();
const getMyBooth = vi.fn();

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: {
    getSlots: () => getSlots(),
    getMyBooth: () => getMyBooth(),
    leaseSlot: vi.fn(),
  },
}));

const { SlotListPage } = await import('../../SlotListPage');
const { __resetSessionForTests, markBootstrapped, setGuestSession } = await import(
  '../../../../features/auth/model/session'
);

// 실서버 응답 그대로의 11필드(2026-08-27 local BE GET /booth-slots 실측)
function slot(overrides: Partial<SlotView>): SlotView {
  return {
    slotId: 1,
    slotCode: 'F11-R01',
    floorNo: 11,
    type: 'USER_RENTAL',
    status: 'AVAILABLE',
    boothId: null,
    boothName: null,
    leaseEndsAt: null,
    remainingSeconds: null,
    entryAvailable: false,
    mine: false,
    ...overrides,
  };
}

function renderPage() {
  // retry:false — 오류 분기를 보려면 재시도 대기가 없어야 한다
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SlotListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
  getMyBooth.mockResolvedValue(null);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  __resetSessionForTests();
});

describe('SlotListPage — -87 완료 조건', () => {
  it('조회 중에는 로딩 상태를 보여준다', () => {
    getSlots.mockReturnValue(new Promise(() => {})); // 끝나지 않는 요청 = 로딩 고정
    renderPage();
    expect(screen.queryByText('슬롯을 불러오는 중...')).not.toBeNull();
  });

  it('조회 실패는 조용히 빈 목록으로 떨어지지 않고 오류 문구를 낸다', async () => {
    getSlots.mockRejectedValue(new Error('network down'));
    renderPage();
    // 실패를 삼키고 빈 목록을 그리는 것이 T-24 의 형태다 — 그 회귀를 막는 줄이다
    expect(await screen.findByText('슬롯 목록을 불러오지 못했습니다')).not.toBeNull();
  });

  it('슬롯 상태 3종을 서로 다르게 표기한다', async () => {
    const endsAt = new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString();
    getSlots.mockResolvedValue([
      slot({ slotId: 1, slotCode: 'F11-R01' }),
      slot({
        slotId: 2, slotCode: 'F11-R02', status: 'OCCUPIED',
        boothId: 7, boothName: '남의 부스', leaseEndsAt: endsAt,
      }),
      slot({
        slotId: 3, slotCode: 'F11-R03', status: 'OCCUPIED',
        boothId: 8, boothName: '내가 빌린 부스', leaseEndsAt: endsAt, mine: true,
      }),
    ]);
    renderPage();
    expect(await screen.findByText('F11-R01')).not.toBeNull();
    const text = document.body.textContent ?? '';
    expect(text).toContain('임대 가능');
    expect(text).toContain('사용 중 — 남의 부스');
    // mine 슬롯은 부스 이름을 노출하지 않고 '내 부스'로만 표기한다 — 이름을 함께 넣으면
    // 부분문자열이 겹쳐 mine 분기를 지워도 통과한다(실효성 실측에서 실제로 통과했다)
    expect(text).toContain('내 부스');
    expect(text).not.toContain('내가 빌린 부스');
  });

  it('OCCUPIED 슬롯은 leaseEndsAt 기준 남은 시간을 보여준다', async () => {
    getSlots.mockResolvedValue([
      slot({
        slotId: 2, slotCode: 'F11-R02', status: 'OCCUPIED',
        boothId: 7, boothName: '남의 부스',
        leaseEndsAt: new Date(Date.now() + 90 * 60 * 1000).toISOString(),
      }),
    ]);
    renderPage();
    expect(await screen.findByText('F11-R02')).not.toBeNull();
    expect(document.body.textContent).toContain('남은 시간');
  });

  it('만료된 leaseEndsAt 은 카운트다운 대신 만료로 표기한다', async () => {
    getSlots.mockResolvedValue([
      slot({
        slotId: 2, slotCode: 'F11-R02', status: 'OCCUPIED',
        boothId: 7, boothName: '남의 부스',
        leaseEndsAt: new Date(Date.now() - 1000).toISOString(),
      }),
    ]);
    renderPage();
    expect(await screen.findByText('F11-R02')).not.toBeNull();
    expect(document.body.textContent).toContain('만료');
  });

  it('게스트에게는 임대 버튼 대신 로그인 안내를 준다', async () => {
    getSlots.mockResolvedValue([slot({})]);
    renderPage();
    expect(await screen.findByText('F11-R01')).not.toBeNull();
    expect(screen.queryByRole('button', { name: /임대/ })).toBeNull();
    expect(document.body.textContent).toContain('임대는 소셜 로그인 회원만 가능합니다.');
  });
});
