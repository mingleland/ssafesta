// @vitest-environment jsdom
// ESC 설정 화면 (S15P21A604-618).
//
// 이 화면은 오래 `disabled` + "준비 중" 이었다. 그래서 재는 것은 **열리는가** 다 —
// 항목 하나뿐이어도, 월드 안에서 소리를 줄일 방법이 아예 없는 것과는 다르다.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { GameMenu } from '../../ui/GameMenu';
import {
  __resetSessionForTests,
  markBootstrapped,
  setGuestSession,
} from '../../../auth/model/session';
import { __resetScreenAudioForTests } from '../../../audio/model/screenAudio';

function renderMenu() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <GameMenu onClose={() => {}} onOpenMyInfo={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  __resetSessionForTests();
  __resetScreenAudioForTests();
  // 게스트로 연다 — 지갑 쿼리가 나가지 않아 이 테스트가 네트워크를 타지 않는다
  setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
});

afterEach(() => {
  cleanup();
  window.localStorage.clear();
  __resetSessionForTests();
  __resetScreenAudioForTests();
});

describe('ESC 설정', () => {
  it('누를 수 있다 — "준비 중" 이 아니다', () => {
    renderMenu();
    const button = screen.getByRole('button', { name: /설정/ });
    expect(button.hasAttribute('disabled')).toBe(false);
    expect(screen.queryByText('준비 중')).toBeNull();
  });

  it('열면 음악 항목이 나오고, 다시 누르면 닫힌다', () => {
    renderMenu();
    const button = screen.getByRole('button', { name: /설정/ });
    expect(screen.queryByRole('switch', { name: '음악' })).toBeNull();

    fireEvent.click(button);
    expect(screen.getByRole('switch', { name: '음악' })).toBeTruthy();
    expect(screen.getByLabelText('크기')).toBeTruthy();

    fireEvent.click(button);
    expect(screen.queryByRole('switch', { name: '음악' })).toBeNull();
  });
});
