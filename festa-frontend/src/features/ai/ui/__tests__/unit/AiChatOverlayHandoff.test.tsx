// @vitest-environment jsdom
// '사람 상담 요청' 버튼의 handoff 게이팅 검증 (S15P21A604-910).
// 정책: handoffEnabled === false 인 부스에서만 버튼을 숨긴다. 값 로딩 전·true 는 버튼을 유지한다
// (-914 계약의 무해 degrade — getBooth 실패/구버전 서버에서 상담 진입이 사라지면 안 된다).
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { BoothDetail } from '../../../../../entities/booth/types';
import { AiChatOverlay } from '../../AiChatOverlay';
import { closeOverlay } from '../../../../../shared/types/overlay';
import { __resetSessionForTests, setMemberSession } from '../../../../auth/model/session';

const { getBoothMock } = vi.hoisted(() => ({ getBoothMock: vi.fn() }));

vi.mock('../../../../../entities/booth/facadeApi.select', () => ({
  facadeApi: { getBooth: getBoothMock },
}));

const FUTURE = new Date(Date.now() + 60_000).toISOString();
const BTN = '사람 상담 요청';

HTMLElement.prototype.scrollTo = vi.fn();

function booth(handoffEnabled?: boolean): BoothDetail {
  return {
    boothId: 1,
    name: '테스트 부스',
    leaseStatus: 'ACTIVE',
    facade: null,
    homepageUrl: null,
    publishedLayoutVersion: 1,
    handoffEnabled,
  };
}

function renderOverlay() {
  return render(
    <MemoryRouter initialEntries={['/app/world']}>
      <AiChatOverlay payload={{ boothId: 1, agentId: 7 }} />
    </MemoryRouter>,
  );
}

afterEach(() => {
  closeOverlay();
  cleanup();
  __resetSessionForTests();
  getBoothMock.mockReset();
});

describe('AiChatOverlay 사람 상담 버튼 handoff 게이팅', () => {
  it('handoffEnabled=true 부스에서는 버튼을 노출한다', async () => {
    setMemberSession('at', FUTURE);
    getBoothMock.mockResolvedValue(booth(true));
    renderOverlay();

    expect(await screen.findByText(BTN)).not.toBeNull();
  });

  it('handoffEnabled=false 부스에서는 버튼을 숨긴다', async () => {
    setMemberSession('at', FUTURE);
    getBoothMock.mockResolvedValue(booth(false));
    renderOverlay();

    // getBooth 반영을 기다린 뒤에도 버튼이 없어야 한다 (부스 이름은 렌더되므로 반영 시점을 잡는다).
    await screen.findByText('테스트 부스');
    expect(screen.queryByText(BTN)).toBeNull();
  });

  it('값 로딩 전(getBooth 미완료)에는 버튼을 유지한다', async () => {
    setMemberSession('at', FUTURE);
    getBoothMock.mockReturnValue(new Promise<BoothDetail>(() => {})); // 영원히 pending
    renderOverlay();

    expect(await screen.findByText(BTN)).not.toBeNull();
  });
});
