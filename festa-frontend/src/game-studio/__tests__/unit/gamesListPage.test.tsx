// @vitest-environment jsdom
// S15P21A604-824 — 목록·생성·삭제·복원·공개설정 토글 회귀 방어.
// 게임 개수는 케이스당 1~2건이면 충분하다 — 이 화면은 GAME_LIMIT_EXCEEDED로 원래 목록이
// 작고, 상한 검증은 API 포트 테스트(gameAuthoringApi.test.ts)에서 409 mock으로 이미 본다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { GameSummary } from '../../studio/ports/gameAuthoringApi.ts';

const list = vi.fn();
const create = vi.fn();
const remove = vi.fn();
const restore = vi.fn();
const setVisibility = vi.fn();

// importOriginal로 GameAuthoringApiError 등 나머지 export는 실제 모듈 그대로 둔다 — 포트가
// 실제로 던지는 예외 모양(!1029 리뷰: plain object가 아니라 GameAuthoringApiError 인스턴스)을
// mock에서도 그대로 써야, mock이 실제와 다른 모양으로 새서 회귀를 못 잡는 일이 안 생긴다.
vi.mock('../../studio/ports/gameAuthoringApi.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../studio/ports/gameAuthoringApi.ts')>();
  return {
    ...actual,
    createApiGameLibraryPort: () => ({ list, create, remove, restore }),
    createApiGameVisibilityPort: () => ({ get: vi.fn(), set: setVisibility }),
  };
});

const { GamesListPage } = await import('../../app/routes/GamesListPage.tsx');
const { GameAuthoringApiError } = await import('../../studio/ports/gameAuthoringApi.ts');

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
    // 포트는 항상 GameAuthoringApiError만 던진다(plain object가 아니다) — !1029 리뷰에서
    // 이 mock이 plain object라 실제 버그(isApiError가 절대 안 걸림)를 못 잡았던 자리다.
    create.mockRejectedValue(new GameAuthoringApiError('GAME_LIMIT_EXCEEDED', '게임은 최대 10개까지 만들 수 있습니다.'));
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

    await screen.findByRole('button', { name: '🔒 비공개' });
    expect(restore).toHaveBeenCalledWith(2);
  });

  it('공개설정 토글을 누르면 반대 값으로 전환 요청하고 표시가 바뀐다', async () => {
    const priv = game({ gameId: 3, title: '토글 게임', visibility: 'PRIVATE' });
    const pub: GameSummary = { ...priv, visibility: 'PUBLIC' };
    list.mockResolvedValueOnce([priv]).mockResolvedValueOnce([pub]);
    setVisibility.mockResolvedValue(pub);
    renderPage();
    await screen.findByText('토글 게임');

    fireEvent.click(screen.getByRole('button', { name: '🔒 비공개' }));

    await screen.findByRole('button', { name: '🌐 공개됨' });
    expect(setVisibility).toHaveBeenCalledWith(3, 'PUBLIC');
  });

  // !1029 리뷰 반영 — 이하 4건
  it('삭제 재시도(GAME_DELETED)는 오류 배너 없이 목록만 갱신한다', async () => {
    const live = game({ gameId: 1, title: '이미 삭제된 게임' });
    list.mockResolvedValueOnce([live]).mockResolvedValueOnce([]);
    remove.mockRejectedValue(new GameAuthoringApiError('GAME_DELETED', '이미 삭제된 게임입니다.'));
    renderPage();
    await screen.findByText('이미 삭제된 게임');

    fireEvent.click(screen.getByRole('button', { name: '삭제' }));

    // 목록이 빈 상태로 갱신될 때까지 기다린다 — invalidate가 실제로 일어났다는 신호다.
    await screen.findByText('아직 만든 게임이 없습니다. 위에서 제목을 입력해 첫 게임을 만들어보세요.');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('한 게임의 공개설정 전환이 진행 중이어도 다른 게임의 버튼은 잠기지 않는다', async () => {
    const a = game({ gameId: 1, title: '게임 A', visibility: 'PRIVATE' });
    const b = game({ gameId: 2, title: '게임 B', visibility: 'PRIVATE' });
    list.mockResolvedValue([a, b]);
    setVisibility.mockReturnValue(new Promise(() => {})); // 끝나지 않는 요청 = pending 고정
    renderPage();
    await screen.findByText('게임 A');

    const [toggleA, toggleB] = screen.getAllByRole('button', { name: '🔒 비공개' }) as HTMLButtonElement[];
    fireEvent.click(toggleA);

    // mutation의 isPending 반영은 리렌더를 한 번 거친다 — click 직후 동기적으로는 안 보인다.
    // jest-dom 매처(toBeDisabled 등)는 이 프로젝트에 설치돼 있지 않다 — DOM 프로퍼티로 직접 본다.
    await waitFor(() => expect(toggleA.disabled).toBe(true));
    expect(toggleB.disabled).toBe(false);
  });

  it('삭제 실패(그 외 사유) 배너에 대상 게임 제목이 포함된다', async () => {
    const live = game({ gameId: 1, title: '삭제 실패할 게임' });
    list.mockResolvedValue([live]);
    remove.mockRejectedValue(new GameAuthoringApiError('GAME_FORBIDDEN', '내 게임이 아닙니다.'));
    renderPage();
    await screen.findByText('삭제 실패할 게임');

    fireEvent.click(screen.getByRole('button', { name: '삭제' }));

    await screen.findByText('삭제 실패할 게임 — 내 게임이 아닙니다.');
  });

  it('살아있는 게임이 없고 삭제한 게임만 있으면 안내 문구가 다르게 뜬다', async () => {
    const deleted = game({ gameId: 1, title: '삭제된 게임', deletedAt: '2026-09-16T01:00:00Z' });
    list.mockResolvedValue([deleted]);
    renderPage();

    await screen.findByText('제작 중인 게임이 없습니다. 위에서 새로 만들거나, 아래 삭제한 게임을 복원할 수 있습니다.');
    expect(screen.queryByText('아직 만든 게임이 없습니다. 위에서 제목을 입력해 첫 게임을 만들어보세요.')).toBeNull();
  });
});
