import { useEffect, useMemo, useState } from 'react';
import { useResolvedAssetUrls, type GameAssetResolver } from '../../studio/assets/useResolvedAssetUrls.ts';
import { createPublishedGameSessionPort, type GameSessionPort } from '../ports/gameSessionPort.ts';
import {
  createApiPublishedGameRepository,
  normalizePublishedGameLoadError,
  PublishedGameLoadError,
  type PublishedGameRepository,
  type PublishedGameSnapshot,
} from '../ports/publishedGameRepository.ts';
import { ReferenceGamePlayer } from '../reference/ReferenceGamePlayer.tsx';
import { GameRuntimeErrorBoundary } from './GameRuntimeErrorBoundary.tsx';

const NO_ASSETS = [] as const;

interface PublishedGameSurfaceProps {
  readonly gameId: number;
  readonly onExit: () => void;
  readonly repository?: PublishedGameRepository;
  readonly sessionPort?: GameSessionPort;
  readonly assetResolver?: GameAssetResolver | null;
}

type LoadState =
  | { readonly status: 'loading'; readonly attempt: number }
  | { readonly status: 'ready'; readonly attempt: number; readonly snapshot: PublishedGameSnapshot }
  | { readonly status: 'error'; readonly attempt: number; readonly error: PublishedGameLoadError };

export const PublishedGameSurface = ({
  gameId,
  onExit,
  repository: repositoryProp,
  sessionPort: sessionPortProp,
  assetResolver = null,
}: PublishedGameSurfaceProps) => {
  const repository = useMemo(() => repositoryProp ?? createApiPublishedGameRepository(), [repositoryProp]);
  const sessionPort = useMemo(() => sessionPortProp ?? createPublishedGameSessionPort(), [sessionPortProp]);
  const [state, setState] = useState<LoadState>({ status: 'loading', attempt: 0 });
  const project = state.status === 'ready' ? state.snapshot.project : null;
  const assetUrls = useResolvedAssetUrls(project?.assets ?? NO_ASSETS, assetResolver);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    const attempt = state.attempt;
    setState({ status: 'loading', attempt });
    repository.load(gameId, controller.signal)
      .then((snapshot) => {
        if (active) setState({ status: 'ready', attempt, snapshot });
      })
      .catch((error: unknown) => {
        const normalized = normalizePublishedGameLoadError(error);
        if (active && normalized.code !== 'ABORTED') setState({ status: 'error', attempt, error: normalized });
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [gameId, repository, state.attempt]);

  const retry = () => setState((current) => ({ status: 'loading', attempt: current.attempt + 1 }));

  if (state.status === 'loading') {
    return <main className="grp-loading" aria-live="polite"><strong>FESTA Game Player</strong><p>게시된 게임을 불러오는 중입니다.</p></main>;
  }
  if (state.status === 'error') {
    return (
      <main className="grp-loading" role="alert">
        <strong>게임을 열 수 없습니다</strong>
        <p>{state.error.message}</p>
        {state.error.requestId !== undefined && <small>문의 코드: {state.error.requestId}</small>}
        <div>
          {state.error.retryable && <button onClick={retry} type="button">다시 시도</button>}
          <button onClick={onExit} type="button">나가기</button>
        </div>
      </main>
    );
  }
  return (
    <GameRuntimeErrorBoundary
      onExit={onExit}
      onRetry={retry}
      resetKey={`${state.snapshot.gameId}:${state.snapshot.publishedVersion}:${state.attempt}`}
    >
      <ReferenceGamePlayer
        mode="PUBLISHED"
        assetUrls={assetUrls}
        onExit={onExit}
        project={state.snapshot.project}
        sessionPort={sessionPort}
      />
    </GameRuntimeErrorBoundary>
  );
};
