// 내 게임 목록·생성 화면 — /app/games (S15P21A604-824).
// 편집(/edit)·플레이(/play) 화면은 있었지만 그 앞단계(목록을 보고 새로 만드는 화면)가 아예
// 없었던 공백을 채운다. 이 화면으로 들어오는 진입점(어디에 링크를 둘지)은 범위 밖이다 —
// ESC 메뉴는 user-flow-decisions.md §12가 명시적으로 막고 있어 팀 논의로 별도 결정한다.
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createApiGameLibraryPort,
  createApiGameVisibilityPort,
  type GameSummary,
  type GameVisibility,
} from '../../studio/ports/gameAuthoringApi.ts';
import { isApiError } from '../../../shared/api/client.ts';
import { PageShell, ScreenError, ScreenLoading } from '../../../features/shell/ui/PageShell.tsx';
import './gamesListPage.css';

const libraryApi = createApiGameLibraryPort();
const visibilityApi = createApiGameVisibilityPort();

const GAMES_QUERY_KEY = ['games-mine'];

function createErrorText(error: unknown): string {
  if (!isApiError(error)) return '게임을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.';
  // GAME_LIMIT_EXCEEDED의 상한 값은 서버 message에만 있다(계약) — 그대로 보여준다.
  if (error.code === 'GAME_LIMIT_EXCEEDED') return error.message;
  if (error.code === 'VALIDATION_FAILED') return '제목은 1~100자여야 합니다.';
  return error.message;
}

function actionErrorText(error: unknown, fallback: string): string {
  return isApiError(error) ? error.message : fallback;
}

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR');
}

export function GamesListPage() {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState('');

  const gamesQuery = useQuery({ queryKey: GAMES_QUERY_KEY, queryFn: () => libraryApi.list() });
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: GAMES_QUERY_KEY });

  const createMutation = useMutation({
    mutationFn: (value: string) => libraryApi.create(value),
    onSuccess: () => {
      setTitle('');
      invalidate();
    },
  });
  const removeMutation = useMutation({ mutationFn: (gameId: number) => libraryApi.remove(gameId), onSuccess: invalidate });
  const restoreMutation = useMutation({ mutationFn: (gameId: number) => libraryApi.restore(gameId), onSuccess: invalidate });
  const visibilityMutation = useMutation({
    mutationFn: ({ gameId, next }: { gameId: number; next: GameVisibility }) => visibilityApi.set(gameId, next),
    onSuccess: invalidate,
  });

  if (gamesQuery.isLoading) {
    return (
      <PageShell title="내 게임" backTo="/app/world">
        <ScreenLoading label="게임 목록을 불러오는 중..." />
      </PageShell>
    );
  }
  if (gamesQuery.isError) {
    return (
      <PageShell title="내 게임" backTo="/app/world">
        <ScreenError title="게임 목록을 불러오지 못했습니다" onRetry={() => void gamesQuery.refetch()} />
      </PageShell>
    );
  }

  const games: readonly GameSummary[] = gamesQuery.data ?? [];
  const liveGames = games.filter((game) => game.deletedAt === null);
  const deletedGames = games.filter((game) => game.deletedAt !== null);

  return (
    <PageShell title="내 게임" subtitle={`제작 중인 게임 ${liveGames.length}개`} backTo="/app/world">
      <form
        className="sc-card games-create"
        onSubmit={(event) => {
          event.preventDefault();
          const trimmed = title.trim();
          if (trimmed.length > 0) createMutation.mutate(trimmed);
        }}
      >
        <input
          className="games-create-input"
          type="text"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          placeholder="새 게임 제목"
          maxLength={100}
          disabled={createMutation.isPending}
          aria-label="새 게임 제목"
        />
        <button
          type="submit"
          className="sc-btn sc-btn-primary"
          disabled={createMutation.isPending || title.trim().length === 0}
        >
          새 게임 만들기
        </button>
      </form>
      {createMutation.isError && (
        <p className="sc-alert" role="alert">{createErrorText(createMutation.error)}</p>
      )}

      {liveGames.length === 0 ? (
        <p className="sc-note games-empty">아직 만든 게임이 없습니다. 위에서 제목을 입력해 첫 게임을 만들어보세요.</p>
      ) : (
        <ul className="games-list">
          {liveGames.map((game) => (
            <li key={game.gameId} className="sc-card games-card">
              <div className="games-card-head">
                <strong>{game.title}</strong>
                <span className="sc-note">#{game.gameId}</span>
                <span className={game.visibility === 'PUBLIC' ? 'sc-chip sc-chip-ok' : 'sc-chip'}>
                  {game.visibility === 'PUBLIC' ? '공개' : '비공개'}
                </span>
              </div>
              <p className="sc-note">
                {game.publishedVersion === null ? '게시한 적 없음' : `게시됨 v${game.publishedVersion}`}
                {' · 마지막 수정 '}
                {formatDateTime(game.updatedAt)}
              </p>
              <div className="games-card-actions">
                <button
                  type="button"
                  className="sc-btn"
                  aria-pressed={game.visibility === 'PUBLIC'}
                  disabled={visibilityMutation.isPending}
                  title={game.visibility === 'PUBLIC'
                    ? '클릭하면 비공개로 전환합니다. 게스트가 더 이상 플레이할 수 없습니다.'
                    : '클릭하면 공개로 전환합니다. 게시된 버전을 게스트도 플레이할 수 있게 됩니다.'}
                  onClick={() => visibilityMutation.mutate({
                    gameId: game.gameId,
                    next: game.visibility === 'PUBLIC' ? 'PRIVATE' : 'PUBLIC',
                  })}
                >
                  {/* 편집기(GameStudioShell, 701)와 라벨·아이콘·툴팁을 그대로 통일한다 — "지금
                      상태"를 보여주는 표기다, "누르면 될 상태"가 아니다. 둘이 반대로 읽히면
                      같은 게임을 목록과 편집기 어느 쪽에서 보든 헷갈린다. */}
                  {game.visibility === 'PUBLIC' ? '🌐 공개됨' : '🔒 비공개'}
                </button>
                <Link className="sc-btn sc-btn-primary" to={`/app/games/${game.gameId}/edit`}>
                  편집하기
                </Link>
                <button
                  type="button"
                  className="sc-btn games-danger"
                  disabled={removeMutation.isPending}
                  onClick={() => removeMutation.mutate(game.gameId)}
                >
                  삭제
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {removeMutation.isError && (
        <p className="sc-alert" role="alert">{actionErrorText(removeMutation.error, '게임을 삭제하지 못했습니다.')}</p>
      )}
      {visibilityMutation.isError && (
        <p className="sc-alert" role="alert">{actionErrorText(visibilityMutation.error, '공개설정을 변경하지 못했습니다.')}</p>
      )}

      {deletedGames.length > 0 && (
        <>
          <h2 className="sc-section-title games-section-title">삭제한 게임</h2>
          <ul className="games-list">
            {deletedGames.map((game) => (
              <li key={game.gameId} className="sc-card games-card">
                <div className="games-card-head">
                  <strong>{game.title}</strong>
                  <span className="sc-note">#{game.gameId}</span>
                </div>
                {/* deletedGames로 걸러진 항목이라 deletedAt은 항상 문자열이다 */}
                <p className="sc-note">{formatDateTime(game.deletedAt as string)} 삭제됨</p>
                <div className="games-card-actions">
                  <button
                    type="button"
                    className="sc-btn sc-btn-primary"
                    disabled={restoreMutation.isPending}
                    onClick={() => restoreMutation.mutate(game.gameId)}
                  >
                    복원
                  </button>
                </div>
              </li>
            ))}
          </ul>
          {restoreMutation.isError && (
            <p className="sc-alert" role="alert">{actionErrorText(restoreMutation.error, '게임을 복원하지 못했습니다.')}</p>
          )}
        </>
      )}
    </PageShell>
  );
}
