import { useEffect, useMemo, useRef, useState } from 'react';
import { closeOverlay } from '../../shared/types/overlay.ts';
import {
  createApiGamePortalRepository,
  GamePortalLoadError,
  gamePortalUnavailableMessage,
  type GamePortalRepository,
  type GamePortalResolution,
} from '../runtime/ports/gamePortalRepository.ts';
import { PublishedGameSurface } from '../runtime/ui/PublishedGameSurface.tsx';
import './GameOverlay.css';

export interface GameOverlayPayload {
  readonly boothId: number;
  readonly objectId: string;
  readonly configId: number;
}

interface GameOverlayProps {
  readonly payload: GameOverlayPayload;
  readonly portalRepository?: GamePortalRepository;
}

type PortalState =
  | { readonly status: 'loading'; readonly attempt: number }
  | { readonly status: 'ready'; readonly attempt: number; readonly resolution: GamePortalResolution }
  | { readonly status: 'error'; readonly attempt: number; readonly error: GamePortalLoadError };

export const GameOverlay = ({ payload, portalRepository: repositoryProp }: GameOverlayProps) => {
  const repository = useMemo(() => repositoryProp ?? createApiGamePortalRepository(), [repositoryProp]);
  const [state, setState] = useState<PortalState>({ status: 'loading', attempt: 0 });
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const active = document.activeElement;
    if (active instanceof HTMLElement) active.blur();
    rootRef.current?.focus();
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      closeOverlay();
    };
    window.addEventListener('keydown', closeOnEscape, true);
    return () => window.removeEventListener('keydown', closeOnEscape, true);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    const attempt = state.attempt;
    setState({ status: 'loading', attempt });
    repository.resolve(payload, controller.signal)
      .then((resolution) => {
        if (active) setState({ status: 'ready', attempt, resolution });
      })
      .catch((error: unknown) => {
        if (!active || (error instanceof DOMException && error.name === 'AbortError')) return;
        setState({
          status: 'error',
          attempt,
          error: error instanceof GamePortalLoadError
            ? error
            : new GamePortalLoadError('게임 포털을 불러오지 못했습니다.', { retryable: true }),
        });
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [payload, repository, state.attempt]);

  const retry = () => setState((current) => ({ status: 'loading', attempt: current.attempt + 1 }));

  return (
    <div aria-label="FESTA 게임" aria-modal="true" className="ggo-overlay" ref={rootRef} role="dialog" tabIndex={-1}>
      {state.status === 'loading' && (
        <section className="ggo-message" aria-live="polite"><span>F</span><h1>게임에 입장하는 중입니다</h1><p>게시 상태와 부스 연결을 확인하고 있습니다.</p></section>
      )}
      {state.status === 'error' && (
        <section className="ggo-message" role="alert">
          <span>!</span><h1>게임을 열 수 없습니다</h1><p>{state.error.message}</p>
          {state.error.requestId !== undefined && <small>문의 코드: {state.error.requestId}</small>}
          <div>{state.error.retryable && <button onClick={retry} type="button">다시 시도</button>}<button onClick={closeOverlay} type="button">월드로 돌아가기</button></div>
        </section>
      )}
      {state.status === 'ready' && !state.resolution.playable && (
        <section className="ggo-message" role="alert"><span>◇</span><h1>지금은 플레이할 수 없습니다</h1><p>{gamePortalUnavailableMessage(state.resolution.unavailableReason)}</p><button onClick={closeOverlay} type="button">월드로 돌아가기</button></section>
      )}
      {state.status === 'ready' && state.resolution.playable && state.resolution.gameId !== null && (
        <PublishedGameSurface gameId={state.resolution.gameId} onExit={closeOverlay} />
      )}
    </div>
  );
};
