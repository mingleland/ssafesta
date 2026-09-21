import { afterEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '../../../../../shared/api/client';
import {
  PROJECT_LOGO_PREVIEW_MAX_BYTES,
  validateProjectLogo,
  uploadProjectLogo,
  fetchProjectLogo,
} from '../../logoUpload';

describe('project logo preview validation', () => {
  it('PNG/JPEG/GIF/WebP 파일만 허용한다', () => {
    expect(validateProjectLogo(new File(['x'], 'logo.png', { type: 'image/png' }))).toBeNull();
    expect(validateProjectLogo(new File(['x'], 'logo.svg', { type: 'image/svg+xml' })))
      .toBe('PNG, JPG, GIF, WebP 이미지만 선택할 수 있습니다.');
    expect(validateProjectLogo(new File(['x'], 'logo.gif', { type: 'image/gif' }))).toBeNull();
  });

  it('미리보기 안전 상한보다 큰 파일을 거부한다', () => {
    const file = new File([new Uint8Array(PROJECT_LOGO_PREVIEW_MAX_BYTES + 1)], 'large.webp', {
      type: 'image/webp',
    });
    expect(validateProjectLogo(file)).toBe('이미지는 5MB 이하만 선택할 수 있습니다.');
  });
});

afterEach(() => { vi.unstubAllGlobals(); setAccessToken(null); });

const path = '/api/v1/booths/7/project-logos';
const file = new File(['image'], 'logo.png', { type: 'image/png' });
function uploadResponses(result: object, putStatus = 200) {
  return vi.fn()
    .mockResolvedValueOnce(Response.json({ logoId: 'logo-1', uploadUrl: 'https://storage.example/upload', requiredContentType: 'image/png' }))
    .mockResolvedValueOnce(new Response(null, { status: putStatus }))
    .mockResolvedValueOnce(Response.json(result));
}

it('grant → 서명된 PUT → complete, READY 경로만 반환하며 저장소에 토큰을 보내지 않는다', async () => {
  setAccessToken('member-token');
  const fetch = uploadResponses({ status: 'READY', url: `${path}/logo-1/content` });
  vi.stubGlobal('fetch', fetch);
  expect(await uploadProjectLogo(7, file)).toEqual({ thumbnailUrl: `${path}/logo-1/content` });
  expect(fetch.mock.calls[0][0]).toContain(path);
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ contentType: 'image/png', byteSize: 5 });
  expect(fetch.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer member-token');
  expect(fetch.mock.calls[1]).toEqual(['https://storage.example/upload', expect.objectContaining({
    method: 'PUT', headers: { 'Content-Type': 'image/png' }, credentials: 'omit', body: file,
  })]);
  expect(fetch.mock.calls[2][0]).toContain(`${path}/logo-1/complete`);
  expect(fetch.mock.calls[2][1].body).toBeUndefined();
});

it('complete HTTP 200이라도 FAILED면 저장 경로를 반환하지 않는다', async () => {
  vi.stubGlobal('fetch', uploadResponses({ status: 'FAILED', failureRule: 'DIMENSION_EXCEEDED', url: null }));
  await expect(uploadProjectLogo(7, file)).rejects.toThrow('4096px');
});

it('PUT 실패 뒤 complete를 부르지 않는다', async () => {
  const fetch = uploadResponses({}, 403);
  vi.stubGlobal('fetch', fetch);
  await expect(uploadProjectLogo(7, file)).rejects.toThrow('업로드하지 못했습니다');
  expect(fetch).toHaveBeenCalledTimes(2);
});

it('다른 부스나 외부 경로를 complete가 반환하면 거부한다', async () => {
  vi.stubGlobal('fetch', uploadResponses({ status: 'READY', url: 'https://other.example/logo' }));
  await expect(uploadProjectLogo(7, file)).rejects.toThrow('일치하지 않습니다');
});

it('편집자 로고만 인증 fetch하며 외부 URL로 토큰을 보내지 않는다', async () => {
  setAccessToken('member-token');
  const fetch = vi.fn().mockResolvedValue(new Response('image'));
  vi.stubGlobal('fetch', fetch);
  await fetchProjectLogo(`${path}/logo-1/content`, new AbortController().signal);
  expect(fetch.mock.calls[0][1].headers).toEqual({ Authorization: 'Bearer member-token' });
  await expect(fetchProjectLogo('https://other.example/logo', new AbortController().signal)).rejects.toThrow();
  expect(fetch).toHaveBeenCalledTimes(1);
});
