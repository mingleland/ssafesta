// @vitest-environment jsdom
// 정지 계정 안내 (S15P21A604-433, spec 001 AS-5) — 서버가 `?error=` 로 돌려보낸 경우
// complete() 를 부르지 않고 사실에 맞는 안내를 보인다. 파라미터가 없으면 기존 흐름 그대로.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

const complete = vi.fn();
vi.mock('../../../../entities/auth/api.select', () => ({ authApi: { complete: () => complete() } }));

async function renderAt(search: string) {
  vi.resetModules();
  const { CallbackPage } = await import('../../CallbackPage');
  return render(
    <MemoryRouter initialEntries={[`/auth/callback${search}`]}>
      <Routes>
        <Route element={<CallbackPage />} path="/auth/callback" />
        <Route element={<p>로그인 화면</p>} path="/login" />
      </Routes>
    </MemoryRouter>,
  );
}

afterEach(() => { cleanup(); complete.mockReset(); });

describe('CallbackPage — 서버가 돌려보낸 경우 (-433)', () => {
  it('ACCOUNT_SUSPENDED 면 상태와 후속 행동을 안내한다 — "만료" 로 뭉뚱그리지 않는다', async () => {
    await renderAt('?error=ACCOUNT_SUSPENDED');
    expect(screen.getByText('이용이 제한된 계정이에요')).toBeTruthy();
    expect(screen.getByText(/운영자에게 문의해 제한 해제를 요청/)).toBeTruthy();
    expect(screen.queryByText('입장 정보가 만료되었어요')).toBeNull();
  });

  it('정지 안내에서는 complete() 를 부르지 않는다 — handoff 는 이미 서버가 지웠다', async () => {
    await renderAt('?error=ACCOUNT_SUSPENDED');
    await waitFor(() => expect(screen.getByText('이용이 제한된 계정이에요')).toBeTruthy());
    expect(complete).not.toHaveBeenCalled();
  });

  it('알 수 없는 error 값은 재시작 화면으로 보내되 complete() 는 건너뛴다', async () => {
    await renderAt('?error=SOMETHING_NEW');
    expect(screen.getByText('입장 정보가 만료되었어요')).toBeTruthy();
    expect(complete).not.toHaveBeenCalled();
  });

  it('error 파라미터가 없으면 기존 흐름대로 complete() 를 부른다', async () => {
    complete.mockImplementation(() => new Promise(() => {}));
    await renderAt('');
    await waitFor(() => expect(complete).toHaveBeenCalledTimes(1));
    expect(screen.getByText('축제에 입장하고 있어요...')).toBeTruthy();
  });
});
