// @vitest-environment jsdom
// S15P21A604-547 — "플레이 테스트"는 별도 라우트(/app/games/:id/play)로 이동하는 구조라,
// 나갔다 돌아오면 에디터(GameStudioShell)가 통째로 새로 마운트된다. 이때 정상 초안 로드
// 콜백이 무조건 project.startSceneId로 선택 씬을 되돌리던 것을, 마지막으로 보고 있던
// 씬을 localStorage에 기억했다가 복원하도록 바꾼다. 실제 라우트 이동을 재현하긴 어려우니
// 같은 gameId·같은 repository로 GameStudioShell을 언마운트했다가 다시 마운트하는 것으로
// 그 상황을 흉내 낸다 — 이게 정확히 재현하려는 조건(내용은 안 바뀌었는데 컴포넌트만
// 새로 마운트됨)과 동일하다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { addTopDownScene } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameDraftRepository } from '../../studio/ports/draftRepository.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.localStorage.clear();
});

// repository.load()가 미리 넣어둔 project를 실제 구현처럼 Promise로 돌려주는 최소 mock —
// GameStudioShell의 "정상 초안 로드" 경로(repository.load 이후 setSelectedSceneId(...))를
// 실제로 태워야 이번 결함이 재현/검증된다(repository=null로 렌더하는 다른 테스트들은 이
// 경로 자체를 안 탄다).
const createMockRepository = (initialDraft: GameProject): GameDraftRepository => {
  let stored = initialDraft;
  return {
    load: async () => stored,
    save: async (project) => {
      stored = project;
      return { savedAt: new Date().toISOString(), revision: project.revision, warnings: [] };
    },
  };
};

const buildTwoSceneProject = (gameId: number): GameProject => addTopDownScene(createStarterProject(gameId));

const panelHeadingText = (container: HTMLElement): string | null => (
  container.querySelector('.gss-panel-heading h2')?.textContent ?? null
);

const clickSceneRow = (container: HTMLElement, sceneName: string) => {
  const nameButton = [...container.querySelectorAll('.gss-scene-row button')]
    .find((button) => button.textContent?.includes(sceneName) && !button.className.includes('gss-drag-handle'));
  if (nameButton === undefined) throw new Error(`Scene row "${sceneName}"을 찾을 수 없다`);
  fireEvent.click(nameButton);
};

const renderShell = async (repository: GameDraftRepository, gameId: number) => {
  const { container, unmount } = render(
    <MemoryRouter>
      <GameStudioShell assetRepository={null} gameId={gameId} publisher={null} repository={repository} />
    </MemoryRouter>,
  );
  // repository.load가 끝나 실제 초안이 반영될 때까지 기다린다(알림 문구로 확인 —
  // GameStudioShell.tsx의 `${persistenceLabel}에 저장한 초안을 불러왔습니다.`).
  await screen.findByText(/초안을 불러왔습니다/);
  return { container, unmount };
};

describe('GameStudioShell — 플레이 테스트 왕복 후 선택 씬 유지(S15P21A604-547)', () => {
  it('시작 씬이 아닌 다른 씬을 보고 있다가 재마운트해도 그 씬이 그대로 유지된다', async () => {
    const GAME_ID = 5471;
    const draft = buildTwoSceneProject(GAME_ID);
    const secondSceneName = draft.scenes.at(-1)!.name;
    const repository = createMockRepository(draft);

    const { container, unmount } = await renderShell(repository, GAME_ID);
    clickSceneRow(container, secondSceneName);
    expect(panelHeadingText(container)).toBe(secondSceneName);
    unmount();

    // 플레이 테스트에서 나가 EditGamePage/GameStudioShell이 통째로 재마운트되는 상황을
    // 흉내 낸다 — 같은 gameId, 내용이 안 바뀐 같은 repository로 다시 렌더.
    const { container: remounted } = await renderShell(repository, GAME_ID);
    expect(panelHeadingText(remounted)).toBe(secondSceneName);
  });

  it('저장된 선택 씬이 더 이상 존재하지 않으면(삭제 등) 시작 씬으로 돌아간다', async () => {
    const GAME_ID = 5472;
    const draft = buildTwoSceneProject(GAME_ID);
    const secondSceneName = draft.scenes.at(-1)!.name;
    const repository = createMockRepository(draft);

    const { container, unmount } = await renderShell(repository, GAME_ID);
    clickSceneRow(container, secondSceneName);
    unmount();

    // 두 번째 씬이 사라진(예: 삭제된) 다른 초안으로 교체한 뒤 재마운트한다.
    const startOnlyProject = createStarterProject(GAME_ID);
    const otherRepository = createMockRepository(startOnlyProject);
    const { container: remounted } = await renderShell(otherRepository, GAME_ID);
    expect(panelHeadingText(remounted)).toBe(startOnlyProject.scenes.find((scene) => scene.id === startOnlyProject.startSceneId)?.name);
  });

  it('서로 다른 gameId는 마지막 선택 씬을 독립적으로 기억한다', async () => {
    const GAME_A = 5473;
    const GAME_B = 5474;
    const draftA = buildTwoSceneProject(GAME_A);
    const draftB = buildTwoSceneProject(GAME_B);
    const secondSceneNameA = draftA.scenes.at(-1)!.name;
    const startSceneNameB = draftB.scenes.find((scene) => scene.id === draftB.startSceneId)!.name;

    const { container: containerA, unmount: unmountA } = await renderShell(createMockRepository(draftA), GAME_A);
    clickSceneRow(containerA, secondSceneNameA);
    unmountA();

    // GAME_B는 한 번도 다른 씬을 선택한 적이 없으니, GAME_A의 선택 기록과 무관하게
    // 자기 자신의 시작 씬을 보여줘야 한다.
    const { container: containerB } = await renderShell(createMockRepository(draftB), GAME_B);
    expect(panelHeadingText(containerB)).toBe(startSceneNameB);
  });
});
