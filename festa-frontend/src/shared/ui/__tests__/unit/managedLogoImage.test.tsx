// @vitest-environment jsdom
// 관리 로고는 <img> 직접 요청이 편집자 분기에서 404 다 — 토큰付き fetch 로 받는지 본다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { isManagedLogoUrl, ManagedLogoImage } from '../../ManagedLogoImage';
import * as client from '../../../api/client';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('isManagedLogoUrl', () => {
  it('BE 발급 경로 모양만 가로챈다', () => {
    expect(isManagedLogoUrl('/api/v1/booths/7/project-logos/abc/content')).toBe(true);
    expect(isManagedLogoUrl('https://cdn.example/logo.webp')).toBe(false);
    expect(isManagedLogoUrl('blob:preview')).toBe(false);
    expect(isManagedLogoUrl('/api/v1/booths/7/project-logos/abc/content?x=1')).toBe(false);
  });
});

describe('ManagedLogoImage', () => {
  it('관리 경로는 토큰을 실어 fetch 하고 blob URL 을 건다', async () => {
    vi.spyOn(client, 'getAccessToken').mockReturnValue('token-1');
    const blob = new Blob(['img'], { type: 'image/png' });
    const fetchMock = vi.fn(() => Promise.resolve({ ok: true, blob: () => Promise.resolve(blob) }));
    vi.stubGlobal('fetch', fetchMock);
    const createMock = vi.fn(() => 'blob:managed');
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createMock });
    const revokeMock = vi.fn();
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeMock });

    render(<ManagedLogoImage src="/api/v1/booths/7/project-logos/abc/content" alt="로고" />);

    const image = (await screen.findByRole('img', { name: '로고' })) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe('blob:managed');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/booths/7/project-logos/abc/content',
      expect.objectContaining({ headers: { Authorization: 'Bearer token-1' } }),
    );
  });

  it('외부 URL 은 fetch 없이 그대로 건다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    render(<ManagedLogoImage src="https://cdn.example/logo.webp" alt="로고" />);

    expect((screen.getByRole('img', { name: '로고' }) as HTMLImageElement).getAttribute('src')).toBe(
      'https://cdn.example/logo.webp',
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('조회 실패는 onError 로 넘기고 img 를 그리지 않는다', async () => {
    vi.spyOn(client, 'getAccessToken').mockReturnValue('token-1');
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 404 })));
    const onError = vi.fn();

    render(<ManagedLogoImage src="/api/v1/booths/7/project-logos/gone/content" alt="로고" onError={onError} />);

    await waitFor(() => expect(onError).toHaveBeenCalled());
    expect(screen.queryByRole('img')).toBeNull();
  });
});
