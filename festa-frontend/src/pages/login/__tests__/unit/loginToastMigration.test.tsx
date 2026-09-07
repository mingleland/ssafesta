// @vitest-environment jsdom
// 로그인 알림의 Toast 이관 (S15P21A604-465).
//
// 핵심은 문구가 아니라 **패널이 알림에 영향받지 않는다**는 것이다. 알림이 패널 안에 있으면
// 낮은 화면에서 버튼을 밀어 푸터를 뚫는다(vh 500 실측: 상단 notice +49px, 하단 오류 +68px).
// jsdom 은 레이아웃을 계산하지 않으므로 여기서는 "패널 안에 알림 노드가 없다" 를 잠근다 —
// 실제 높이 회귀는 브라우저 실측으로 닫는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { __resetToastsForTests, getToastsSnapshot } from '../../../../shared/ui/toast/toastStore';
import { __resetSessionForTests, markBootstrapped } from '../../../../features/auth/model/session';

vi.mock('../../../../unity/host/warmup', () => ({ warmUpUnityAssets: () => {} }));

const guestEnter = vi.fn();
vi.mock('../../../../entities/auth/api.select', () => ({
  authApi: { guestEnter: () => guestEnter() },
}));

beforeEach(() => {
  __resetToastsForTests();
  __resetSessionForTests();
  markBootstrapped();
  guestEnter.mockReset();
});

afterEach(cleanup);

async function renderLogin() {
  const { LoginPage } = await import('../../LoginPage');
  return render(
    <MemoryRouter>
      <LoginPage />
    </MemoryRouter>,
  );
}

describe('패널은 알림에 영향받지 않는다', () => {
  it('패널 안에 알림 노드가 없다 — 있으면 낮은 화면에서 버튼을 민다', async () => {
    const { container } = await renderLogin();
    expect(container.querySelector('.login-panel .login-alert')).toBeNull();
  });

  it('게스트 입장이 실패해도 패널의 자식 수가 그대로다', async () => {
    guestEnter.mockRejectedValue(new Error('boom'));
    const { container } = await renderLogin();
    const panel = container.querySelector('.login-panel');
    const before = panel?.childElementCount;

    fireEvent.click(screen.getByRole('button', { name: /게스트로 둘러보기/ }));
    await waitFor(() => expect(getToastsSnapshot()).toHaveLength(1));

    expect(panel?.childElementCount).toBe(before);
  });
});

describe('알림은 토스트로 나간다', () => {
  it('게스트 입장 실패는 error 토스트다', async () => {
    guestEnter.mockRejectedValue(new Error('boom'));
    await renderLogin();

    fireEvent.click(screen.getByRole('button', { name: /게스트로 둘러보기/ }));
    await waitFor(() => expect(getToastsSnapshot()).toHaveLength(1));
    expect(getToastsSnapshot()[0].kind).toBe('error');
  });

  it('연타해도 같은 오류가 쌓이지 않는다', async () => {
    guestEnter.mockRejectedValue(new Error('boom'));
    await renderLogin();

    const button = screen.getByRole('button', { name: /게스트로 둘러보기/ });
    fireEvent.click(button);
    await waitFor(() => expect(getToastsSnapshot()).toHaveLength(1));
    fireEvent.click(button);
    await waitFor(() => expect(guestEnter).toHaveBeenCalledTimes(2));

    expect(getToastsSnapshot()).toHaveLength(1);
  });
});
