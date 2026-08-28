// @vitest-environment jsdom
// PlayGamePage 가 Asset resolver 를 실제로 넘기는지 검사한다 — 어댑터 동작이 아니라 "배선"이 대상이다.
// remoteAssetRepository 를 직접 import 하지 않는 것이 이 파일의 핵심 제약이다: 어댑터를 손수 만들어
// 검사하는 테스트는 페이지가 그것을 아무 데도 안 꽂아도 전부 통과하고, 실제로 그래서 이 결함이
// remoteAssetRepository.test.ts 17건을 통과한 채 develop 에 남아 있었다 (S15P21A604-317).
// 주입 지점은 전역 fetch 하나뿐이다 — 우리 모듈을 mock 하지 않아야 "페이지가 넘기는가"를 묻게 된다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { PlayGamePage } from '../../app/routes/PlayGamePage.tsx';
import { cloneMinimalGameProject, type DeepMutable } from '../fixtures/minimalGameProject.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

const GAME_ID = 123; // minimalGameProject.gameId — 응답과 프로젝트의 gameId 가 어긋나면 파서가 거부한다
const PUBLISHED_PATH = `/api/v1/games/${GAME_ID}/published`;
const CONTENT_PATH = `/api/v1/games/${GAME_ID}/assets/spriteA/content`;

const projectWithAssetSource = (source: string): DeepMutable<GameProject> => {
  const project = cloneMinimalGameProject();
  const asset = project.assets.find((candidate) => candidate.id === 'playerImage');
  if (asset === undefined) throw new Error('fixture 가 바뀌었다 — playerImage 자산이 없다');
  asset.source = source;
  return project;
};

const jsonResponse = (body: unknown, status = 200): Response => new Response(
  JSON.stringify(body),
  { status, headers: { 'Content-Type': 'application/json' } },
);

interface FetchStub {
  readonly calls: { url: string; headers: Headers }[];
}

const installFetch = (project: DeepMutable<GameProject>, contentOk = true): FetchStub => {
  const calls: { url: string; headers: Headers }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, headers: new Headers(init?.headers) });
    if (url.includes(PUBLISHED_PATH)) {
      return jsonResponse({
        gameId: GAME_ID,
        publishedVersion: 1,
        schemaVersion: project.schemaVersion,
        project,
      });
    }
    if (url.includes('/assets/') && url.endsWith('/content')) {
      if (!contentOk) {
        return jsonResponse({ code: 'GAME_ASSET_NOT_FOUND', message: '없음', errors: [], warnings: [] }, 404);
      }
      return new Response(new Blob(['fake-image-bytes']), { status: 200 });
    }
    return jsonResponse({ code: 'UNKNOWN', message: 'unexpected', errors: [], warnings: [] }, 404);
  }));
  return { calls };
};

const renderPlayPage = () => render(
  <MemoryRouter initialEntries={[`/app/games/${GAME_ID}/play`]}>
    <Routes>
      <Route element={<PlayGamePage />} path="/app/games/:gameId/play" />
    </Routes>
  </MemoryRouter>,
);

const contentCalls = (stub: FetchStub) => stub.calls.filter((call) => call.url.endsWith('/content'));

let createObjectURL: ReturnType<typeof vi.fn>;

beforeEach(() => {
  // jsdom 에는 없다. object URL 생성 자체가 "해석이 끝까지 갔다"의 관측 지점이라 세어 둔다.
  createObjectURL = vi.fn(() => 'blob:festa-test');
  vi.stubGlobal('URL', Object.assign(URL, {
    createObjectURL,
    revokeObjectURL: vi.fn(),
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('PlayGamePage — 원격 Asset resolver 배선', () => {
  // jsdom 에는 indexedDB 가 없다 — 로컬 저장소가 빈 "새 세션" 조건이 그대로 성립한다.
  it('익명 Published 플레이에서 asset:// 참조를 서버로 조회한다', async () => {
    const stub = installFetch(projectWithAssetSource(`asset://game/${GAME_ID}/spriteA`));
    renderPlayPage();

    await waitFor(() => {
      // base URL 은 배포 설정이라(VITE_API_BASE_URL) 경로만 단언한다 — 정확히 1건이어야 한다.
      expect(contentCalls(stub).map((call) => call.url)).toEqual([expect.stringContaining(CONTENT_PATH)]);
    });
    // 토큰을 세우지 않았으므로 Authorization 이 붙지 않아야 한다 — 익명 플레이가 이 경로를 탄다.
    expect(contentCalls(stub)[0].headers.has('Authorization')).toBe(false);
  });

  it('조회한 자산을 object URL 로 만들어 플레이어까지 내린다', async () => {
    installFetch(projectWithAssetSource(`asset://game/${GAME_ID}/spriteA`));
    renderPlayPage();

    await waitFor(() => {
      expect(createObjectURL).toHaveBeenCalledTimes(1);
    });
  });

  it('asset://local 참조는 서버로 새지 않는다', async () => {
    const stub = installFetch(projectWithAssetSource('asset://local/spriteA'));
    renderPlayPage();

    // 게임이 그려질 때까지 기다린 뒤 판정한다 — 렌더 전에 세면 항상 0 이라 무의미하다.
    await waitFor(() => {
      expect(screen.queryByText('게시된 게임을 불러오는 중입니다.')).toBeNull();
    });
    expect(contentCalls(stub)).toHaveLength(0);
  });

  it('자산 조회가 404 여도 오류 화면 대신 게임을 그린다', async () => {
    installFetch(projectWithAssetSource(`asset://game/${GAME_ID}/spriteA`), false);
    renderPlayPage();

    await waitFor(() => {
      expect(screen.queryByText('게시된 게임을 불러오는 중입니다.')).toBeNull();
    });
    // 자산 하나를 못 받은 것은 게임을 못 여는 사유가 아니다 — useResolvedAssetUrls 가 {} 로 되돌린다.
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
