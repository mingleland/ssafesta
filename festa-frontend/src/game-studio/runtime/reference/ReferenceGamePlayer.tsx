import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { DEFAULT_GAME_RULES, findScene, type AssetReference, type GameObjective, type GameProject, type TileLayer } from '../../contracts/gameProject.ts';
import { getActiveDialogue, getAvailableDialogueChoices } from '../dialogue/dialogueRunner.ts';
import { findBuiltinSpriteSheet } from '../../studio/assets/builtinAssetCatalog.ts';
import { findPresetDefinition } from '../../studio/model/authoringRegistry.ts';
import { SpriteAnimationPreview } from '../../studio/ui/SpriteAnimationPreview.tsx';
import { resolveTilesetVisual, tileBackgroundStyle } from '../../studio/assets/tilesetVisual.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../../studio/assets/staticImageVisual.ts';
import type { GameSessionPort } from '../ports/gameSessionPort.ts';
import { summarizeFramePerformance, type FramePerformanceSummary } from './framePerformance.ts';
import {
  chooseReferenceDialogue,
  interactReferencePlayer,
  moveReferencePlayer,
  objectiveProgress,
  shootReferenceProjectile,
  startReferenceRuntime,
  tickReferenceWorld,
  type MoveDirection,
} from './referenceRuntime.ts';
import './ReferenceGamePlayer.css';

interface ReferenceGamePlayerProps {
  readonly project: GameProject;
  readonly mode: 'PREVIEW' | 'PUBLISHED';
  readonly sessionPort: GameSessionPort;
  readonly assetUrls?: Readonly<Record<string, string>>;
  readonly onExit: () => void;
  readonly showPerformanceMonitor?: boolean;
}

const keyDirection = (key: string): MoveDirection | null => {
  if (key === 'ArrowUp' || key.toLowerCase() === 'w') return 'UP';
  if (key === 'ArrowDown' || key.toLowerCase() === 's') return 'DOWN';
  if (key === 'ArrowLeft' || key.toLowerCase() === 'a') return 'LEFT';
  if (key === 'ArrowRight' || key.toLowerCase() === 'd') return 'RIGHT';
  return null;
};

interface TileLayersProps {
  readonly layers: readonly TileLayer[];
  readonly sceneWidth: number;
  readonly assets: readonly AssetReference[];
  readonly assetUrls: Readonly<Record<string, string>>;
}

// scene.tileLayers는 project와 함께만 바뀌므로 memo로 감싸 runtime tick(120ms)마다 최대 10,000개
// 타일 span을 다시 그리지 않게 한다 (S15P21A604-263). tileset도 레이어당 한 번만 resolve —
// 타일마다 project.assets.find를 반복하면 fixture=max에서 그 자체가 재조정 비용의 큰 비중을 차지했다.
const TileLayers = memo(({ layers, sceneWidth, assets, assetUrls }: TileLayersProps) => (
  <>
    {layers.map((layer) => {
      const visual = resolveTilesetVisual(assets.find((asset) => asset.id === layer.tilesetAssetId), assetUrls);
      return (
        <div className="grp-tile-layer" key={layer.id}>
          {layer.data.map((tile, index) => tile < 0 ? null : (
            <span
              className={`is-tile-${tile % 8}`}
              key={`${layer.id}-${index}`}
              style={{
                gridColumn: (index % sceneWidth) + 1,
                gridRow: Math.floor(index / sceneWidth) + 1,
                ...(visual === null ? {} : tileBackgroundStyle(visual, tile)),
              }}
            />
          ))}
        </div>
      );
    })}
  </>
));
TileLayers.displayName = 'TileLayers';

const objectiveCopy = (objective: GameObjective): string => {
  if (objective.type === 'SCORE_AT_LEAST') return `${objective.target.toLocaleString('ko-KR')}점 달성`;
  if (objective.type === 'DEFEAT_ENEMIES') return `적 ${objective.target.toLocaleString('ko-KR')}명 처치`;
  return `${objective.target.toLocaleString('ko-KR')}초 생존`;
};

export const ReferenceGamePlayer = ({ project, mode, sessionPort, assetUrls = {}, onExit, showPerformanceMonitor = false }: ReferenceGamePlayerProps) => {
  const [runtime, setRuntime] = useState(() => startReferenceRuntime(project));
  const [sessionToken, setSessionToken] = useState<string | null>(null);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [framePerformance, setFramePerformance] = useState<FramePerformanceSummary | null>(null);
  const [performanceTabActive, setPerformanceTabActive] = useState(() => (
    typeof document === 'undefined' || document.visibilityState === 'visible'
  ));
  const completionReported = useRef(false);
  const sessionTokenRef = useRef<string | null>(null);
  const sessionEndedRef = useRef(false);
  const scene = findScene(project, runtime.session.currentSceneId);
  const activeDialogue = getActiveDialogue(project, runtime.session);
  const choices = activeDialogue === null ? [] : getAvailableDialogueChoices(project, runtime.session);
  const playerAsset = project.assets.find((asset) => asset.source === 'builtin://sprites/player');
  const playerSheet = playerAsset === undefined ? undefined : findBuiltinSpriteSheet(playerAsset.source);
  const playerClip = playerSheet?.clips.find((clip) => clip.id === `walk${runtime.facing[0]}${runtime.facing.slice(1).toLowerCase()}`);
  const mapBackground = scene !== undefined && scene.type !== 'DIALOGUE'
    ? resolveStaticImageVisual(project.assets.find((asset) => asset.id === scene.backgroundAssetId), assetUrls)
    : null;
  const dialogueBackground = activeDialogue === null
    ? null
    : resolveStaticImageVisual(project.assets.find((asset) => asset.id === activeDialogue.scene.backgroundAssetId), assetUrls);
  const dialoguePortrait = activeDialogue === null
    ? null
    : resolveStaticImageVisual(project.assets.find((asset) => asset.id === activeDialogue.node.portraitAssetId), assetUrls);
  const canShoot = scene !== undefined && scene.type !== 'DIALOGUE' && scene.objects.some((object) => (
    object.preset === 'PLAYER_SPAWN' && object.components.some((component) => component.type === 'SHOOTER')
  ));
  const completionRules = (project.rules ?? DEFAULT_GAME_RULES).completion;

  useEffect(() => {
    let active = true;
    sessionEndedRef.current = false;
    sessionTokenRef.current = null;
    setSessionToken(null);
    setSessionError(null);
    sessionPort.start({ gameId: project.gameId, mode })
      .then((result) => {
        if (!active) {
          void sessionPort.exit(result.sessionToken).catch(() => undefined);
          return;
        }
        sessionTokenRef.current = result.sessionToken;
        setSessionToken(result.sessionToken);
      })
      .catch((error: unknown) => { if (active) setSessionError(error instanceof Error ? error.message : '게임 세션을 시작하지 못했습니다.'); });
    return () => {
      active = false;
      const token = sessionTokenRef.current;
      if (token !== null && !sessionEndedRef.current) {
        sessionEndedRef.current = true;
        void sessionPort.exit(token).catch(() => undefined);
      }
    };
  }, [mode, project.gameId, sessionPort]);

  useEffect(() => {
    if (runtime.session.status !== 'COMPLETED' || sessionToken === null || completionReported.current) return;
    completionReported.current = true;
    sessionEndedRef.current = true;
    void sessionPort.complete(sessionToken).catch((error: unknown) => {
      setSessionError(error instanceof Error ? error.message : '완료 결과를 전송하지 못했습니다.');
    });
  }, [runtime.session.status, sessionPort, sessionToken]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const direction = keyDirection(event.key);
      if (direction !== null) {
        event.preventDefault();
        setRuntime((current) => moveReferencePlayer(project, current, direction));
        return;
      }
      if (event.key.toLowerCase() === 'f') {
        event.preventDefault();
        setRuntime((current) => shootReferenceProjectile(project, current));
        return;
      }
      if (event.key === 'Enter' || event.key.toLowerCase() === 'e' || event.key === ' ') {
        if (activeDialogue !== null) return;
        event.preventDefault();
        setRuntime((current) => interactReferencePlayer(project, current));
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [activeDialogue, project]);

  useEffect(() => {
    if (scene === undefined || scene.type === 'DIALOGUE' || activeDialogue !== null || runtime.session.status !== 'PLAYING') return undefined;
    const timer = window.setInterval(() => {
      setRuntime((current) => tickReferenceWorld(project, current));
    }, 120);
    return () => window.clearInterval(timer);
  }, [activeDialogue, project, runtime.session.status, scene?.type]);

  useEffect(() => {
    if (!showPerformanceMonitor) {
      setFramePerformance(null);
      return undefined;
    }
    let frameId = 0;
    let frameDurations: number[] = [];
    let measuredAt = performance.now();
    let previousFrameAt: number | null = null;
    const resetWindow = (now: number) => {
      frameDurations = [];
      measuredAt = now;
      previousFrameAt = null;
    };
    const onVisibilityChange = () => {
      const active = document.visibilityState === 'visible';
      setPerformanceTabActive(active);
      resetWindow(performance.now());
      if (!active) setFramePerformance(null);
    };
    const measure = (now: number) => {
      if (document.visibilityState === 'visible') {
        if (previousFrameAt !== null) frameDurations.push(now - previousFrameAt);
        previousFrameAt = now;
        const elapsed = now - measuredAt;
        if (elapsed >= 1_000) {
          setFramePerformance(summarizeFramePerformance(frameDurations, elapsed));
          resetWindow(now);
        }
      } else {
        previousFrameAt = null;
      }
      frameId = window.requestAnimationFrame(measure);
    };
    setPerformanceTabActive(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', onVisibilityChange);
    frameId = window.requestAnimationFrame(measure);
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.cancelAnimationFrame(frameId);
    };
  }, [showPerformanceMonitor]);

  const inventory = useMemo(() => [...runtime.session.inventory].map((itemId) => (
    project.items.find((item) => item.id === itemId)?.name ?? itemId
  )), [project.items, runtime.session.inventory]);

  const exit = () => {
    if (sessionToken !== null && !sessionEndedRef.current) {
      sessionEndedRef.current = true;
      void sessionPort.exit(sessionToken).catch(() => undefined);
    }
    onExit();
  };

  if (sessionError !== null) {
    return <main className="grp-loading"><strong>게임을 시작할 수 없습니다</strong><p>{sessionError}</p><button onClick={onExit} type="button">돌아가기</button></main>;
  }

  return (
    <main className="grp-root" data-game-studio-runtime="reference">
      <header className="grp-topbar">
        <div><span className="grp-brand">F</span><strong>{project.title}</strong><em>{mode === 'PREVIEW' ? 'PLAY TEST' : 'FESTA GAME'}</em></div>
        <div>
          <span>Scene</span><strong>{scene?.name ?? runtime.session.currentSceneId}</strong>
          {showPerformanceMonitor && (!performanceTabActive
            ? <em className="is-low">비활성 탭 · 측정 일시정지</em>
            : framePerformance === null
              ? <em>성능 측정 준비</em>
              : (
                <span
                  aria-label={`성능 측정 FPS ${framePerformance.fps}, 95퍼센타일 ${framePerformance.p95FrameMs}밀리초, 느린 프레임 ${framePerformance.slowFramePercent}퍼센트`}
                  className="grp-perf-monitor"
                  role="status"
                  title="활성 탭에서 1초 단위로 측정합니다. 목표: 55fps 이상, p95 18.2ms 이하, 느린 프레임 5% 이하"
                >
                  <em className={framePerformance.meetsTarget ? 'is-good' : 'is-low'}>FPS {framePerformance.fps}</em>
                  <em className={framePerformance.p95FrameMs <= 1_000 / 55 ? 'is-good' : 'is-low'}>p95 {framePerformance.p95FrameMs}ms</em>
                  <em className={framePerformance.slowFramePercent <= 5 ? 'is-good' : 'is-low'}>끊김 {framePerformance.slowFramePercent}%</em>
                </span>
              ))}
        </div>
        <button onClick={exit} type="button">게임 나가기 ×</button>
      </header>

      <section className="grp-stage-wrap">
        {scene !== undefined && scene.type !== 'DIALOGUE' && runtime.playerPosition !== null && (
          <div
            aria-label={`${scene.name} 플레이 화면`}
            className="grp-map"
            style={{
              aspectRatio: `${scene.width} / ${scene.height}`,
              ...(mapBackground === null ? {} : staticImageBackgroundStyle(mapBackground)),
              '--grp-columns': scene.width,
              '--grp-rows': scene.height,
            } as React.CSSProperties}
          >
            <TileLayers assetUrls={assetUrls} assets={project.assets} layers={scene.tileLayers} sceneWidth={scene.width} />
            {scene.objects.filter((object) => (
              object.preset !== 'PLAYER_SPAWN' && runtime.session.objectVisibility[object.id] !== false
            )).map((object) => {
              const definition = findPresetDefinition(object.preset);
              const runtimePosition = runtime.objectPositions[object.id] ?? object.position;
              const sprite = object.components.find((component) => component.type === 'SPRITE');
              const spriteVisual = sprite?.type === 'SPRITE'
                ? resolveStaticImageVisual(project.assets.find((asset) => asset.id === sprite.assetId), assetUrls)
                : null;
              return (
                <span
                  className={`grp-object grp-object--${object.preset.toLowerCase()}`}
                  key={object.id}
                  style={{
                    left: `${((runtimePosition.x + .5) / scene.width) * 100}%`,
                    top: `${((runtimePosition.y + .5) / scene.height) * 100}%`,
                    transform: `translate(-50%,-50%) scale(${sprite?.type === 'SPRITE' ? (sprite.scale ?? 100) / 100 : 1})`,
                    zIndex: sprite?.type === 'SPRITE' ? 10 + (sprite.zIndex ?? 2) : 12,
                  }}
                  title={definition.label}
                >{spriteVisual === null ? definition.icon : <span className="grp-static-sprite" style={staticImageBackgroundStyle(spriteVisual)} />}</span>
              );
            })}
            {runtime.spawnedEnemies.map((enemy) => {
              const visual = resolveStaticImageVisual(project.assets.find((asset) => asset.id === enemy.assetId), assetUrls);
              return <span className="grp-dynamic-enemy" key={enemy.id} style={{ left: `${((enemy.position.x + .5) / scene.width) * 100}%`, top: `${((enemy.position.y + .5) / scene.height) * 100}%` }}>{visual === null ? '♟' : <span style={staticImageBackgroundStyle(visual)} />}</span>;
            })}
            {runtime.projectiles.map((projectile) => {
              const visual = resolveStaticImageVisual(project.assets.find((asset) => asset.id === projectile.assetId), assetUrls);
              return <span className={`grp-projectile is-${projectile.owner.toLowerCase()}`} key={projectile.id} style={{ left: `${((projectile.position.x + .5) / scene.width) * 100}%`, top: `${((projectile.position.y + .5) / scene.height) * 100}%` }}>{visual === null ? '•' : <span style={staticImageBackgroundStyle(visual)} />}</span>;
            })}
            <span
              className="grp-player"
              style={{
                left: `${((runtime.playerPosition.x + .5) / scene.width) * 100}%`,
                top: `${((runtime.playerPosition.y + .5) / scene.height) * 100}%`,
              }}
            >
              {playerSheet === undefined ? '◆' : <SpriteAnimationPreview clip={playerClip} sheet={playerSheet} size={72} />}
            </span>
          </div>
        )}
        {scene?.type === 'DIALOGUE' && (
          <div
            className="grp-story-backdrop"
            style={dialogueBackground === null ? undefined : staticImageBackgroundStyle(dialogueBackground)}
          ><strong>{dialogueBackground === null ? scene.name : ''}</strong></div>
        )}

        {activeDialogue !== null && (
          <section className={`grp-dialogue-layer${activeDialogue.scene.presentation === 'FULL_SCREEN' ? ' is-fullscreen' : ''}`}>
            {activeDialogue.scene.presentation === 'FULL_SCREEN' && dialogueBackground !== null && (
              <div className="grp-dialogue-full-background" style={staticImageBackgroundStyle(dialogueBackground)} />
            )}
            {dialoguePortrait !== null && <div className="grp-dialogue-portrait" style={staticImageBackgroundStyle(dialoguePortrait)} />}
            <div className="grp-dialogue">
              <div className="grp-speaker">{activeDialogue.node.speaker || '내레이션'}</div>
              <p>{activeDialogue.node.text}</p>
              <div className="grp-choices">
                {choices.map((choice, index) => (
                  <button
                    key={choice.id}
                    onClick={() => setRuntime((current) => chooseReferenceDialogue(project, current, choice.id))}
                    type="button"
                  ><span>{index + 1}</span>{choice.text}</button>
                ))}
              </div>
            </div>
          </section>
        )}

        {runtime.session.status === 'COMPLETED' && (
          <section className="grp-result">
            <span>★</span><h1>게임 완료!</h1><p>제작자가 만든 모든 목표를 달성했습니다.</p>
            <div><button onClick={() => { completionReported.current = false; setRuntime(startReferenceRuntime(project)); }} type="button">다시 플레이</button><button onClick={exit} type="button">FESTA로 돌아가기</button></div>
          </section>
        )}
        {runtime.session.status === 'FAILED' && (
          <section className="grp-result is-error">
            <span>!</span>
            <h1>{runtime.session.failure?.code === 'PLAYER_DEFEATED' ? '도전 실패' : '게임 실행 오류'}</h1>
            <p>{runtime.session.failure?.message}</p>
            {runtime.session.failure?.code === 'PLAYER_DEFEATED'
              ? <div><button onClick={() => setRuntime(startReferenceRuntime(project))} type="button">다시 도전</button><button onClick={exit} type="button">게임 나가기</button></div>
              : <button onClick={exit} type="button">편집기로 돌아가기</button>}
          </section>
        )}
      </section>

      <aside className="grp-hud">
        <div className="grp-player-stats"><span>상태</span><strong>♥ {runtime.playerHealth} / {runtime.maxPlayerHealth}</strong><strong>★ {runtime.score.toLocaleString('ko-KR')}점</strong></div>
        {completionRules.objectives.length > 0 && (
          <div className="grp-objectives">
            <span>게임 목표 · {completionRules.mode === 'ALL' ? '모두 달성' : '하나 달성'}</span>
            {completionRules.objectives.map((objective) => {
              const progress = objectiveProgress(runtime, objective);
              const completed = progress >= objective.target;
              return <strong className={completed ? 'is-complete' : ''} key={objective.type}><i>{completed ? '✓' : '○'}</i>{objectiveCopy(objective)}<small>{Math.min(progress, objective.target).toLocaleString('ko-KR')} / {objective.target.toLocaleString('ko-KR')}</small></strong>;
            })}
          </div>
        )}
        <div><span>INVENTORY</span>{inventory.length === 0 ? <small>비어 있음</small> : inventory.map((item) => <strong key={item}>◇ {item}</strong>)}</div>
        <div className="grp-controls"><span>{scene?.type === 'PLATFORMER' ? '이동 / 점프' : '이동'}</span><div><button onClick={() => setRuntime((current) => moveReferencePlayer(project, current, 'UP'))} type="button">↑</button><button onClick={() => setRuntime((current) => moveReferencePlayer(project, current, 'LEFT'))} type="button">←</button><button onClick={() => setRuntime((current) => moveReferencePlayer(project, current, 'DOWN'))} type="button">↓</button><button onClick={() => setRuntime((current) => moveReferencePlayer(project, current, 'RIGHT'))} type="button">→</button></div></div>
        <button className="grp-interact" disabled={activeDialogue !== null} onClick={() => setRuntime((current) => interactReferencePlayer(project, current))} type="button"><kbd>E</kbd> 상호작용</button>
        {canShoot && <button className="grp-shoot" disabled={activeDialogue !== null} onClick={() => setRuntime((current) => shootReferenceProjectile(project, current))} type="button"><kbd>F</kbd> 발사</button>}
      </aside>
    </main>
  );
};
