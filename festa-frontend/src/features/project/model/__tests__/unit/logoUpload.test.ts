import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  PROJECT_LOGO_PREVIEW_MAX_BYTES,
  describeLogoUploadError,
  uploadProjectLogo,
  validateProjectLogo,
} from '../../logoUpload';

describe('project logo preview validation', () => {
  it('PNG/JPEG/WebP 파일만 허용한다', () => {
    expect(validateProjectLogo(new File(['x'], 'logo.png', { type: 'image/png' }))).toBeNull();
    expect(validateProjectLogo(new File(['x'], 'logo.svg', { type: 'image/svg+xml' })))
      .toBe('PNG, JPG, WebP 이미지만 선택할 수 있습니다.');
  });

  it('미리보기 안전 상한보다 큰 파일을 거부한다', () => {
    const file = new File([new Uint8Array(PROJECT_LOGO_PREVIEW_MAX_BYTES + 1)], 'large.webp', {
      type: 'image/webp',
    });
    expect(validateProjectLogo(file)).toBe('이미지는 5MB 이하만 선택할 수 있습니다.');
  });
});

// BE ProjectLogoController(#241) 3단계 계약 — 시작 → presigned PUT → 완료.
// api() 는 모듈 목으로, 저장소 PUT 은 fetch 스텁으로 갈아 끼운다.
const apiCalls: { path: string; init?: RequestInit }[] = [];
vi.mock('../../../../../shared/api/client', () => ({
  api: (path: string, init?: RequestInit) => {
    apiCalls.push({ path, init });
    const queued = mockedResponses.shift();
    if (queued instanceof Error) return Promise.reject(queued);
    return Promise.resolve(queued);
  },
  isApiError: (e: unknown) =>
    typeof e === 'object' && e !== null && typeof (e as { code?: unknown }).code === 'string',
}));

let mockedResponses: unknown[] = [];
let putOk = true;

vi.stubGlobal(
  'fetch',
  vi.fn(() => Promise.resolve({ ok: putOk })),
);

afterEach(() => {
  apiCalls.length = 0;
  mockedResponses = [];
  putOk = true;
  vi.unstubAllGlobals();
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve({ ok: putOk })),
  );
});

function pngFile(): File {
  return new File([new Uint8Array([1, 2, 3])], 'logo.png', { type: 'image/png' });
}

describe('project logo upload adapter', () => {
  it('시작·PUT·완료를 순서대로 부르고 검증된 url 을 돌려준다', async () => {
    mockedResponses = [
      { logoId: 'abc', expiresAt: '2026-09-18T00:00:00Z', uploadUrl: 'https://storage.example/put', requiredContentType: 'image/png' },
      { logoId: 'abc', status: 'READY', url: 'https://be.example/api/v1/booths/7/project-logos/abc/content', failureRule: null },
    ];

    const result = await uploadProjectLogo(pngFile(), 7);

    expect(result).toEqual({ thumbnailUrl: 'https://be.example/api/v1/booths/7/project-logos/abc/content' });
    expect(apiCalls.map((c) => c.path)).toEqual([
      '/api/v1/booths/7/project-logos',
      '/api/v1/booths/7/project-logos/abc/complete',
    ]);
    const startBody = JSON.parse((apiCalls[0].init?.body ?? '{}') as string) as { contentType: string; byteSize: number };
    expect(startBody).toEqual({ contentType: 'image/png', byteSize: 3 });
  });

  it('완료 검증 실패(FAILED)는 재시도 불가로 사용자 문장을 던진다', async () => {
    mockedResponses = [
      { logoId: 'abc', expiresAt: '2026-09-18T00:00:00Z', uploadUrl: 'https://storage.example/put', requiredContentType: 'image/png' },
      { logoId: 'abc', status: 'FAILED', url: null, failureRule: 'DECODE_FAILED' },
    ];

    await expect(uploadProjectLogo(pngFile(), 7)).rejects.toThrow('이미지 파일을 읽을 수 없습니다');
  });

  it('저장소 PUT 실패는 저장소 문장을 던진다', async () => {
    putOk = false;
    mockedResponses = [
      { logoId: 'abc', expiresAt: '2026-09-18T00:00:00Z', uploadUrl: 'https://storage.example/put', requiredContentType: 'image/png' },
    ];

    await expect(uploadProjectLogo(pngFile(), 7)).rejects.toThrow('저장소에 올리지 못했습니다');
  });

  it('미배포(404) 시작 실패는 배포 문장으로 바꾼다', async () => {
    const notFound = { code: 'NOT_FOUND', message: '없음', errors: [], status: 404 };
    mockedResponses = [Object.assign(new Error('404'), notFound)];

    await expect(uploadProjectLogo(pngFile(), 7)).rejects.toThrow();
    expect(describeLogoUploadError(notFound)).toBe('서버에 업로드 기능이 아직 배포되지 않았습니다. 배포 후 다시 시도해 주세요.');
  });
});
