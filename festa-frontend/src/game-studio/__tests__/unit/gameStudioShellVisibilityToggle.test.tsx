// @vitest-environment jsdom
// S15P21A604-701 — 공개설정 토글이 마운트 시 현재 상태를 불러와 표시하고, 클릭하면
// PATCH를 보내 반대 상태로 전환하는지 확인한다. 실패 시 상태를 되돌리지 않고(낙관적
// 갱신을 하지 않으므로 애초에 바뀌지 않는다) 오류만 안내하는지도 함께 본다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameVisibility, GameVisibilityPort } from '../../studio/ports/gameAuthoringApi.ts';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const GAME_ID = 701;

const createSucceedingPort = (initial: GameVisibility): GameVisibilityPort & { readonly setCalls: GameVisibility[] } => {
  let current = initial;
  const setCalls: GameVisibility[] = [];
  return {
    setCalls,
    get: async () => ({ gameId: GAME_ID, visibility: current }),
    set: async (_gameId, visibility) => {
      setCalls.push(visibility);
      current = visibility;
      return { gameId: GAME_ID, visibility: current };
    },
  };
};

// 실제 createApiGameVisibilityPort는 항상 normalizeGameAuthoringError를 거쳐 Error
// 인스턴스만 던진다(gameAuthoringApi.test.ts에서 별도 검증) — 여기서도 그 계약대로 Error를
// 던져야 GameStudioShell의 `error instanceof Error` 분기를 실제와 같은 경로로 태운다.
const createFailingSetPort = (initial: GameVisibility): GameVisibilityPort => ({
  get: async () => ({ gameId: GAME_ID, visibility: initial }),
  set: async () => {
    throw new Error('내 게임이 아닙니다.');
  },
});

const renderShell = (visibilityPort: GameVisibilityPort | null) => render(
  <MemoryRouter>
    <GameStudioShell
      gameId={GAME_ID}
      initialProject={createStarterProject(GAME_ID)}
      publisher={null}
      repository={null}
      visibilityPort={visibilityPort}
    />
  </MemoryRouter>,
);

describe('GameStudioShell — 공개설정 토글(S15P21A604-701)', () => {
  it('마운트 시 현재 공개설정을 불러와 표시하고, 클릭하면 반대 상태로 전환한다', async () => {
    const port = createSucceedingPort('PRIVATE');
    renderShell(port);

    const toggle = await screen.findByRole('button', { name: '🔒 비공개' });
    fireEvent.click(toggle);

    await screen.findByRole('button', { name: '🌐 공개됨' });
    expect(port.setCalls).toEqual(['PUBLIC']);
  });

  it('공개설정 API가 연결되지 않으면(visibilityPort=null) 토글을 표시하지 않는다', () => {
    renderShell(null);
    expect(screen.queryByRole('button', { name: '🔒 비공개' })).toBeNull();
    expect(screen.queryByRole('button', { name: '🌐 공개됨' })).toBeNull();
  });

  it('전환 요청이 실패하면 상태를 바꾸지 않고 서버 오류 메시지를 그대로 안내한다', async () => {
    const port = createFailingSetPort('PRIVATE');
    renderShell(port);

    const toggle = await screen.findByRole('button', { name: '🔒 비공개' });
    fireEvent.click(toggle);

    await screen.findByText('내 게임이 아닙니다.');
    expect(screen.getByRole('button', { name: '🔒 비공개' })).not.toBeNull();
  });
});
