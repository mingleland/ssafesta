// @vitest-environment jsdom
// 로그인 화면 레퍼런스 구조 (S15P21A604-379) — reference: 00_context/sources/login.png
//
// 기하(버튼 좌표·간격·폭)는 jsdom 이 레이아웃을 계산하지 않아 여기서 볼 수 없다. 그쪽은 실제
// 브라우저에서 뷰포트를 1920×1080 으로 고정하고 레퍼런스 실측값과 대조해 닫았다(±0.1%p).
// 이 파일이 잠그는 것은 **레이아웃 없이도 무너질 수 있는 구조**다 — 항목이 빠지거나,
// 준비되지 않은 provider 가 눌리게 되는 회귀.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../../../unity/host/warmup', () => ({ warmUpUnityAssets: () => {} }));

afterEach(cleanup);

async function renderLogin() {
  const { LoginPage } = await import('../../LoginPage');
  return render(
    <MemoryRouter>
      <LoginPage />
    </MemoryRouter>,
  );
}

describe('로그인 레퍼런스 구조 (-379)', () => {
  it('버튼이 레퍼런스 순서로 4종 나온다 — SSAFY → Google → Kakao → 게스트', async () => {
    const { container } = await renderLogin();
    const labels = [...container.querySelectorAll('.login-btn-label')].map((e) => e.textContent);
    expect(labels).toEqual(['SSAFY 로그인', 'Google 로그인', 'Kakao 로그인', '게스트로 둘러보기']);
  });

  it('OAuth 3종이 모두 눌린다 — 준비 중 배지는 남아 있지 않다 (-495)', async () => {
    const { container } = await renderLogin();
    for (const id of ['ssafy', 'google', 'kakao']) {
      const btn = container.querySelector(`.login-btn-${id}`) as HTMLButtonElement;
      expect(btn.disabled).toBe(false);
      expect(within(btn).queryByText('준비 중')).toBeNull();
    }
  });

  it('게스트 버튼은 눌린다 — 유일한 실사용 진입점이다', async () => {
    const { container } = await renderLogin();
    expect((container.querySelector('.login-btn-guest') as HTMLButtonElement).disabled).toBe(false);
  });

  // S15P21A604-815 — 다섯 항목 다 갈 곳이 없어 숨겼다. 대상이 생기면 이 단언을 되돌린다.
  it('푸터를 그리지 않는다 — 누를 수 없는 것을 보여 주지 않는다', async () => {
    const { container } = await renderLogin();
    expect(container.querySelector('.login-footer')).toBeNull();
    for (const label of ['축제 안내', '이벤트', '고객센터', '개인정보처리방침', '이용약관']) {
      expect(screen.queryByText(label)).toBeNull();
    }
  });

  it('배경과 로고를 각각 한 번씩만 그린다 — 로고가 배경에 이미 있지 않다', async () => {
    const { container } = await renderLogin();
    expect(container.querySelectorAll('.login-bg')).toHaveLength(1);
    expect(container.querySelectorAll('.login-logo')).toHaveLength(1);
  });
});
