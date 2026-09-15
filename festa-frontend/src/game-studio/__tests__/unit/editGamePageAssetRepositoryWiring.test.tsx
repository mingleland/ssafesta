// @vitest-environment jsdom
// S15P21A604-116 / GitLab #69 — 편집기가 서버 Asset 저장소를 받는가.
//
// 원격 어댑터(remoteAssetRepository)는 진작 있었고 그 동작은 remoteAssetRepository.test.ts 가,
// 셸이 받은 저장소로 업로드하는 것은 gameStudioShellMultiAssetUpload.test.tsx 가 이미 잠근다.
// 비어 있던 것은 그 사이 seam 하나였다 — EditGamePage 가 서버 저작 모드에서 assetRepository 로
// **null** 을 넘겨, 업로드가 "로컬 저장소를 사용할 수 없습니다" 로 막혔다. 여기서 잠그는 것은
// 그 seam 이다: 플래그가 켜지면 셸이 받는 저장소가 실제로 서버 endpoint 를 부르는가.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

const shell = vi.hoisted(() => ({ props: null as Record<string, unknown> | null }));

vi.mock('../../studio/ui/GameStudioShell.tsx', () => ({
  GameStudioShell: (props: Record<string, unknown>) => {
    shell.props = props;
    return null;
  },
}));

const { EditGamePage } = await import('../../app/routes/EditGamePage.tsx');

const GAME_ID = 116;

const renderEditPage = () => render(
  <MemoryRouter initialEntries={[`/app/games/${GAME_ID}/edit`]}>
    <Routes>
      <Route element={<EditGamePage />} path="/app/games/:gameId/edit" />
    </Routes>
  </MemoryRouter>,
);

beforeEach(() => {
  shell.props = null;
  vi.stubEnv('VITE_USE_MOCK', 'false');
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('EditGamePage — 자산 저장소 배선 (S15P21A604-116)', () => {
  it('서버 저작이 켜지면 셸에 저장소를 넘긴다 — null 이 아니다', () => {
    vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', 'true');
    renderEditPage();

    // null 이면 GameStudioShell.uploadAsset 이 업로드를 시작조차 하지 않는다.
    expect(shell.props?.assetRepository).not.toBeNull();
    expect(shell.props?.assetRepository).toBeTypeOf('object');
  });

  it('그 저장소는 서버 업로드 endpoint 를 부른다 — 로컬 저장소가 아니다', async () => {
    vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', 'true');
    const urls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: unknown) => {
      urls.push(String(input));
      // 첫 호출 주소만 관심사다. 응답 형식은 remoteAssetRepository.test.ts 가 잠근다.
      throw new Error('stub: 업로드 시작 이후는 이 테스트의 관심사가 아니다');
    }));
    renderEditPage();

    const repository = shell.props?.assetRepository as {
      save: (gameId: number, input: { file: File; kind: string }) => Promise<unknown>;
    };
    const file = new File(['x'], 'hero.png', { type: 'image/png' });
    await expect(repository.save(GAME_ID, { file, kind: 'IMAGE' })).rejects.toBeTruthy();

    expect(urls[0]).toContain(`/api/v1/games/${GAME_ID}/assets`);
  });

  it('서버 저작이 꺼져 있으면 undefined 를 넘겨 셸이 브라우저 저장소로 떨어진다', () => {
    vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', 'false');
    renderEditPage();

    // null(저장소 없음)과 undefined(셸 기본값=브라우저)는 다르다 — 이 구분이 이 티켓의 핵심이다.
    expect(shell.props?.assetRepository).toBeUndefined();
  });
});

