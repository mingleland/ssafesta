// @vitest-environment jsdom
// 랜딩·로그인 정적 이미지 (S15P21A604-733).
//
// 첫 화면 표시 시간이 그대로 배경·로고 다운로드 시간이었다 — PNG 로 랜딩 4.29MB · 로그인 4.63MB.
// WebP 로 바꿔 각각 485KB · 574KB 가 됐다(-89%). 여기서 잠그는 것은 그 전환이 조용히 PNG 로
// 되돌아가지 않는 것과, 치수가 빠져 레이아웃이 튀지 않는 것(CLS)이다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../../../unity/host/warmup', () => ({ warmUpUnityAssets: () => {} }));

beforeEach(() => {
  // 이 테스트는 오디오가 아니라 <img> 마크업을 본다. jsdom 은 HTMLMediaElement 재생을 구현하지
  // 않으므로 screenAudio 가 element 를 만들지 않고 빠지게 둔다(mute 테스트와 같은 전제).
  vi.stubGlobal('Audio', undefined);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

async function renderPage(which: 'landing' | 'login') {
  if (which === 'landing') {
    const { LandingPage } = await import('../../LandingPage');
    return render(<MemoryRouter><LandingPage /></MemoryRouter>);
  }
  const { LoginPage } = await import('../../../login/LoginPage');
  return render(<MemoryRouter><LoginPage /></MemoryRouter>);
}

const CASES = [
  { page: 'landing' as const, bg: '.landing-bg', logo: '.landing-logo' },
  { page: 'login' as const, bg: '.login-bg', logo: '.login-logo' },
];

describe.each(CASES)('$page 정적 이미지 (-733)', ({ page, bg, logo }) => {
  it('배경과 로고가 WebP 다 — PNG 로 되돌아가면 첫 화면이 다시 느려진다', async () => {
    const { container } = await renderPage(page);
    for (const sel of [bg, logo]) {
      const img = container.querySelector(sel) as HTMLImageElement;
      expect(img).not.toBeNull();
      expect(img.getAttribute('src')).toMatch(/\.webp($|\?)/);
    }
  });

  it('두 이미지 모두 원본 치수를 들고 있다 — 없으면 도착 전 높이가 0 이라 레이아웃이 튄다', async () => {
    const { container } = await renderPage(page);
    const bgImg = container.querySelector(bg) as HTMLImageElement;
    expect([bgImg.getAttribute('width'), bgImg.getAttribute('height')]).toEqual(['1672', '941']);
    const logoImg = container.querySelector(logo) as HTMLImageElement;
    expect([logoImg.getAttribute('width'), logoImg.getAttribute('height')]).toEqual(['1986', '792']);
  });

  it('preload 는 자기 페이지 배경 하나만 건다', async () => {
    await renderPage(page);
    const links = [...document.querySelectorAll('link[rel="preload"][as="image"]')];
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute('href')).toMatch(/\.webp($|\?)/);
  });
});
