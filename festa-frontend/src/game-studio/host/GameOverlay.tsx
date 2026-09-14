import { useEffect, useMemo, useRef, useState } from 'react';
import { closeOverlay } from '../../shared/types/overlay.ts';
import {
  createApiArcadeMachineRepository,
  createApiGamePortalRepository,
  GamePortalLoadError,
  gamePortalUnavailableMessage,
  type ArcadeMachineRepository,
  type GamePlayableResolution,
  type GamePortalRepository,
} from '../runtime/ports/gamePortalRepository.ts';
import { PublishedGameSurface } from '../runtime/ui/PublishedGameSurface.tsx';
import './GameOverlay.css';

/**
 * 이 오버레이로 들어오는 두 경로.
 *
 * **`kind` 같은 꼬리표를 붙이지 않는다.** 두 모양이 공유하는 필드가 하나도 없어서 식별자 자체로
 * 갈리고, 꼬리표를 만들면 기존 `BOOTH_GAME_INTERACT` 호출부와 그 계약 테스트를 전부 고쳐야 한다.
 */
export type GameOverlayPayload =
  /** 부스 안 GAME_PORTAL — configId 로 푼다 (GitLab #56) */
  | { readonly boothId: number; readonly objectId: string; readonly configId: number }
  /** 광장 오락기 — 씬 canonical id 로 푼다 (S15P21A604-712, GitLab #135) */
  | { readonly machineId: string };

const isArcade = (payload: GameOverlayPayload): payload is { readonly machineId: string } => (
  'machineId' in payload
);

interface GameOverlayProps {
  readonly payload: GameOverlayPayload;
  readonly portalRepository?: GamePortalRepository;
  readonly arcadeRepository?: ArcadeMachineRepository;
}

type PortalState =
  | { readonly status: 'loading'; readonly attempt: number }
  | { readonly status: 'ready'; readonly attempt: number; readonly resolution: GamePlayableResolution }
  | { readonly status: 'error'; readonly attempt: number; readonly error: GamePortalLoadError };

export const GameOverlay = ({ payload, portalRepository, arcadeRepository }: GameOverlayProps) => {
  // 화면은 두 경로가 같다 — 갈리는 것은 어디에 물어보는가 하나뿐이다.
  const resolve = useMemo(() => (
    isArcade(payload)
      ? (signal: AbortSignal) => (arcadeRepository ?? createApiArcadeMachineRepository()).resolve(payload, signal)
      : (signal: AbortSignal) => (portalRepository ?? createApiGamePortalRepository()).resolve(payload, signal)
  ), [payload, portalRepository, arcadeRepository]);
  const [state, setState] = useState<PortalState>({ status: 'loading', attempt: 0 });
  const rootRef = useRef<HTMLDivElement>(null);

  // 게임 런타임이 키를 먹기 전에 focus 를 가져온다.
  //
  // **Esc 리스너는 걷어냈다** (-450, #132). 여기만 capture 단계로 `window` 에 걸려 있어서
  // `WorldPage` 의 중재보다 먼저 오버레이를 닫았고, 그 다음 중재가 "떠 있는 게 없다" 로 읽어
  // **Game Menu 를 함께 열었다.** preventDefault 는 다른 리스너를 막지 못한다. 지금은 `WorldPage`
  // 가 유일한 중재자이고 이 오버레이는 그 화면의 `OverlayHost` 안에서만 뜬다.
  useEffect(() => {
    const active = document.activeElement;
    if (active instanceof HTMLElement) active.blur();
    rootRef.current?.focus();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    const attempt = state.attempt;
    setState({ status: 'loading', attempt });
    resolve(controller.signal)
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
  }, [resolve, state.attempt]);

  const retry = () => setState((current) => ({ status: 'loading', attempt: current.attempt + 1 }));

  return (
    <div aria-label="FESTA 게임" aria-modal="true" className="ggo-overlay" ref={rootRef} role="dialog" tabIndex={-1}>
      {state.status === 'loading' && (
        <section className="ggo-message" aria-live="polite"><span>F</span><h1>게임에 입장하는 중입니다</h1><p>게시 상태와 게임 연결을 확인하고 있습니다.</p></section>
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
