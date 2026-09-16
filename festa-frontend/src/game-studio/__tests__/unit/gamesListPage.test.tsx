// @vitest-environment jsdom
// S15P21A604-824 — 목록·생성·삭제·복원·공개설정 토글 회귀 방어.
// 게임 개수는 케이스당 1~2건이면 충분하다 — 이 화면은 GAME_LIMIT_EXCEEDED로 원래 목록이
// 작고, 상한 검증은 API 포트 테스트(gameAuthoringApi.test.ts)에서 409 mock으로 이미 본다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { GameSummary } from '../../studio/ports/gameAuthoringApi.ts';

const list = vi.fn();
const create = vi.fn();
const remove = vi.fn();
const restore = vi.fn();
const setVisibility = vi.fn();

vi.mock('../../studio/ports/gameAuthoringApi.ts', () => ({
  createApiGameLibraryPort: () => ({ list, create, remove, restore }),
  createApiGameVisibilityPort: () => ({ get: vi.fn(), set: setVisibility }),
}));

const { GamesListPage } = await import('../../app/routes/GamesListPage.tsx');

function game(overrides: Partial<GameSummary>): GameSummary {
  return {
    gameId: 1,
    title: '좀비 탈출',
    visibility: 'PRIVATE',
    publishedVersion: null,
    updatedAt: '2026-09-16T00:00:00Z',
    deletedAt: null,
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <GamesListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  list.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('GamesListPage — S15P21A604-824', () => {
  it('목록이 비어 있으면 안내 문구를 보여준다', async () => {
    renderPage();
    await screen.findByText('아직 만든 게임이 없습니다. 위에서 제목을 입력해 첫 게임을 만들어보세요.');
  });

  it('제목을 입력해 새 게임을 만들면 목록이 갱신된다', async () => {
    list.mockResolvedValueOnce([]).mockResolvedValueOnce([game({ title: '새 게임' })]);
    create.mockResolvedValue(game({ title: '새 게임' }));
    renderPage();
    await screen.findByText('아직 만든 게임이 없습니다. 위에서 제목을 입력해 첫 게임을 만들어보세요.');

    fireEvent.change(screen.getByLabelText('새 게임 제목'), { target: { value: '새 게임' } });
    fireEvent.click(screen.getByRole('button', { name: '새 게임 만들기' }));

    await screen.findByText('새 게임');
    expect(create).toHaveBeenCalledWith('새 게임');
  });

  it('상한 초과(GAME_LIMIT_EXCEEDED) 시 서버 메시지를 그대로 보여준다', async () => {
    create.mockRejectedValue({
      code: 'GAME_LIMIT_EXCEEDED',
      message: '게임은 최대 10개까지 만들 수 있습니다.',
      errors: [],
      warnings: [],
    });
    renderPage();
    await screen.findByText('아직 만든 게임이 없습니다. 위에서 제목을 입력해 첫 게임을 만들어보세요.');

    fireEvent.change(screen.getByLabelText('새 게임 제목'), { target: { value: '초과' } });
    fireEvent.click(screen.getByRole('button', { name: '새 게임 만들기' }));

    await screen.findByText('게임은 최대 10개까지 만들 수 있습니다.');
  });

  it('삭제하면 살아있는 목록에서 사라지고 삭제한 게임 섹션에 나타난다', async () => {
    const live = game({ gameId: 1, title: '삭제될 게임' });
    const deleted = { ...live, deletedAt: '2026-09-16T01:00:00Z' };
    list.mockResolvedValueOnce([live]).mockResolvedValueOnce([deleted]);
    remove.mockResolvedValue(undefined);
    renderPage();
    await screen.findByText('삭제될 게임');

    fireEvent.click(screen.getByRole('button', { name: '삭제' }));

    await screen.findByText('삭제한 게임');
    expect(remove).toHaveBeenCalledWith(1);
  });

  it('삭제한 게임을 복원하면 다시 살아있는 목록으로 돌아온다', async () => {
    const deleted = game({ gameId: 2, title: '복원될 게임', deletedAt: '2026-09-16T01:00:00Z' });
    const restored = { ...deleted, deletedAt: null };
    list.mockResolvedValueOnce([deleted]).mockResolvedValueOnce([restored]);
    restore.mockResolvedValue(restored);
    renderPage();
    await screen.findByText('삭제한 게임');

    fireEvent.click(screen.getByRole('button', { name: '복원' }));

    await screen.findByRole('button', { name: '공개 전환' });
    expect(restore).toHaveBeenCalledWith(2);
  });

  it('공개설정 토글을 누르면 반대 값으로 전환 요청하고 표시가 바뀐다', async () => {
    const priv = game({ gameId: 3, title: '토글 게임', visibility: 'PRIVATE' });
    const pub: GameSummary = { ...priv, visibility: 'PUBLIC' };
    list.mockResolvedValueOnce([priv]).mockResolvedValueOnce([pub]);
    setVisibility.mockResolvedValue(pub);
    renderPage();
    await screen.findByText('토글 게임');

    fireEvent.click(screen.getByRole('button', { name: '공개 전환' }));

    await screen.findByRole('button', { name: '비공개 전환' });
    expect(setVisibility).toHaveBeenCalledWith(3, 'PUBLIC');
  });
});
