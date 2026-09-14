// @vitest-environment jsdom
// S15P21A604-708 — EditGamePage가 서버 모드에서 Asset resolver를 실제로 넘기는지 검사한다.
// playGamePageAssetWiring.test.tsx와 같은 원칙: 우리 모듈은 mock하지 않고 전역 fetch만
// 스텁한다. remoteAssetRepository를 직접 만들어 검사하면 "페이지가 그것을 아무 데도 안
// 꽂아도" 통과하는 테스트가 되고, 실제로 그래서 이 배선 누락이 남아 있었다(EditGamePage.tsx가
// assetRepository를 항상 null로 넘긴 채로).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EditGamePage } from '../../app/routes/EditGamePage.tsx';

const GAME_ID = 708;
const ASSETS_START_PATH = `/api/v1/games/${GAME_ID}/assets`;
const UPLOAD_URL = 'https://fake-upload.test/put';

const jsonResponse = (body: unknown, status = 200): Response => new Response(
  JSON.stringify(body),
  { status, headers: { 'Content-Type': 'application/json' } },
);

interface FetchStub {
  readonly calls: string[];
}

const installFetch = (): FetchStub => {
  const calls: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.endsWith('/draft')) return new Response(null, { status: 204 });
    if (url.endsWith('/mine')) return jsonResponse({ games: [{ gameId: GAME_ID, title: 'T', visibility: 'PRIVATE', publishedVersion: null, updatedAt: 't', deletedAt: null }] });
    if (url.endsWith(ASSETS_START_PATH)) {
      return jsonResponse({
        assetId: 'wiringTestAsset',
        status: 'UPLOADING',
        uploadUrl: UPLOAD_URL,
        requiredHeaders: { 'Content-Type': 'image/png' },
        expiresAt: '2026-09-14T00:10:00Z',
      });
    }
    if (url === UPLOAD_URL) return new Response(null, { status: 200 });
    if (url.endsWith('/assets/wiringTestAsset/complete')) {
      return jsonResponse({
        assetId: 'wiringTestAsset',
        status: 'READY',
        kind: 'IMAGE',
        source: `asset://game/${GAME_ID}/wiringTestAsset`,
        contentType: 'image/png',
        byteSize: 68,
        width: 1,
        height: 1,
      });
    }
    return jsonResponse({ code: 'UNKNOWN', message: 'unexpected', errors: [], warnings: [] }, 404);
  }));
  return { calls };
};

const renderEditPage = () => render(
  <MemoryRouter initialEntries={[`/app/games/${GAME_ID}/edit`]}>
    <Routes>
      <Route element={<EditGamePage />} path="/app/games/:gameId/edit" />
    </Routes>
  </MemoryRouter>,
);

const imageInput = (container: HTMLElement): HTMLInputElement => {
  const input = Array.from(container.querySelectorAll('input[type="file"]')) as HTMLInputElement[];
  const match = input.find((candidate) => candidate.accept.includes('image/gif'));
  if (match === undefined) throw new Error('이미지 추가 input을 찾을 수 없다');
  return match;
};

beforeEach(() => {
  vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', 'true');
  vi.stubEnv('VITE_USE_MOCK', 'false');
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe('EditGamePage — 서버 모드 Asset resolver 배선(S15P21A604-708)', () => {
  it('서버 모드에서 이미지 업로드가 실제로 시작 endpoint(POST /assets)를 호출한다', async () => {
    const stub = installFetch();
    const utils = renderEditPage();
    fireEvent.click(await screen.findByRole('button', { name: '데이터' }));

    const file = new File([new Uint8Array([1, 2, 3])], 'smoke.png', { type: 'image/png' });
    const input = imageInput(utils.container);
    Object.defineProperty(input, 'files', { configurable: true, value: [file] });
    fireEvent.change(input);

    await waitFor(() => {
      expect(stub.calls.some((call) => call.startsWith('POST ') && call.includes(ASSETS_START_PATH))).toBe(true);
    });
    await screen.findByText('smoke.png을 IMAGE 자산으로 추가했습니다.');
  });
});
