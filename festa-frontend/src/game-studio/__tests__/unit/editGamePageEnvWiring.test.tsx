// @vitest-environment jsdom
// S15P21A604-477 — EditGamePage.tsx도 PlayGamePage.tsx(S15P21A604-409)와 똑같은 패턴이다:
// browserPublicationEnabled(= VITE_USE_MOCK && !VITE_GAME_STUDIO_API_ENABLED)를 모듈
// 최상단에서 import 시점에 딱 한 번만 읽는다. 이 파일은 그 증상이 EditGamePage.tsx에도
// 실제로 있는지 재현한다.
//
// -409 테스트는 global fetch를 관찰 지점으로 썼지만, EditGamePage는 "브라우저(mock) vs
// 브라우저" 갈림이 서버 API 호출 여부가 아니라 persistenceLabel 텍스트로만 드러난다
// (repository는 VITE_GAME_STUDIO_API_ENABLED에만 좌우되고 VITE_USE_MOCK과는 무관 — 서버
// 인증이 꺼져 있으면 어느 쪽이든 브라우저 localStorage로 폴백한다). 그래서 여기서는 초안
// 로드 성공 시 GameStudioShell이 띄우는 "{persistenceLabel}에 저장한 초안을 불러왔습니다."
// 알림 문구를 관찰 지점으로 쓴다 — 우리 모듈을 직접 mock하지 않고, localStorage에 미리
// 심어둔 초안 + 실제 렌더 결과로 검증한다는 점에서 -409와 같은 원칙을 따른다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { EditGamePage } from '../../app/routes/EditGamePage.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

const GAME_ID = 456;

const seedBrowserDraft = (): void => {
  const project = createStarterProject(GAME_ID);
  window.localStorage.setItem(`festa:game-studio:draft:v1:${GAME_ID}`, JSON.stringify(project));
};

beforeEach(() => {
  window.localStorage.clear();
  // 서버 인증 경로는 이 테스트의 관심사가 아니다 — 항상 꺼둬서 browserPublicationEnabled
  // (VITE_USE_MOCK)만 변수로 남긴다.
  vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', 'false');
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  window.localStorage.clear();
});

const renderEditPage = () => render(
  <MemoryRouter initialEntries={[`/app/games/${GAME_ID}/edit`]}>
    <Routes>
      <Route element={<EditGamePage />} path="/app/games/:gameId/edit" />
    </Routes>
  </MemoryRouter>,
);

describe('EditGamePage — browserPublicationEnabled(VITE_USE_MOCK) 배선', () => {
  it('VITE_USE_MOCK=true면 "브라우저(Mock)" 라벨로 불러옴 알림이 뜬다', async () => {
    vi.stubEnv('VITE_USE_MOCK', 'true');
    seedBrowserDraft();
    renderEditPage();

    await waitFor(() => {
      expect(screen.queryByText('브라우저(Mock)에 저장한 초안을 불러왔습니다.')).not.toBeNull();
    });
  });

  it('VITE_USE_MOCK=false면 "브라우저" 라벨로 불러옴 알림이 뜬다', async () => {
    vi.stubEnv('VITE_USE_MOCK', 'false');
    seedBrowserDraft();
    renderEditPage();

    await waitFor(() => {
      expect(screen.queryByText('브라우저에 저장한 초안을 불러왔습니다.')).not.toBeNull();
    });
  });
});
