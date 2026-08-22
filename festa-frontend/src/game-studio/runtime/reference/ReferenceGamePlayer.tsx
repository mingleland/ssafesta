import { useEffect, useMemo, useRef, useState } from 'react';
import { findScene, type GameProject } from '../../contracts/gameProject.ts';
import { getActiveDialogue, getAvailableDialogueChoices } from '../dialogue/dialogueRunner.ts';
import { findBuiltinSpriteSheet } from '../../studio/assets/builtinAssetCatalog.ts';
import { findPresetDefinition } from '../../studio/model/authoringRegistry.ts';
import { SpriteAnimationPreview } from '../../studio/ui/SpriteAnimationPreview.tsx';
import { resolveTilesetVisual, tileBackgroundStyle } from '../../studio/assets/tilesetVisual.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../../studio/assets/staticImageVisual.ts';
import type { GameSessionPort } from '../ports/gameSessionPort.ts';
import {
  chooseReferenceDialogue,
  interactReferencePlayer,
  moveReferencePlayer,
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
}

const keyDirection = (key: string): MoveDirection | null => {
  if (key === 'ArrowUp' || key.toLowerCase() === 'w') return 'UP';
  if (key === 'ArrowDown' || key.toLowerCase() === 's') return 'DOWN';
  if (key === 'ArrowLeft' || key.toLowerCase() === 'a') return 'LEFT';
  if (key === 'ArrowRight' || key.toLowerCase() === 'd') return 'RIGHT';
  return null;
};

export const ReferenceGamePlayer = ({ project, mode, sessionPort, assetUrls = {}, onExit }: ReferenceGamePlayerProps) => {
  const [runtime, setRuntime] = useState(() => startReferenceRuntime(project));
  const [sessionToken, setSessionToken] = useState<string | null>(null);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const completionReported = useRef(false);
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

  useEffect(() => {
    let active = true;
    sessionPort.start({ gameId: project.gameId, mode })
      .then((result) => { if (active) setSessionToken(result.sessionToken); })
      .catch((error: unknown) => { if (active) setSessionError(error instanceof Error ? error.message : '게임 세션을 시작하지 못했습니다.'); });
    return () => { active = false; };
  }, [mode, project.gameId, sessionPort]);

  useEffect(() => {
    if (runtime.session.status !== 'COMPLETED' || sessionToken === null || completionReported.current) return;
    completionReported.current = true;
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

  const inventory = useMemo(() => [...runtime.session.inventory].map((itemId) => (
    project.items.find((item) => item.id === itemId)?.name ?? itemId
  )), [project.items, runtime.session.inventory]);

  const exit = () => {
    if (sessionToken !== null && runtime.session.status === 'PLAYING') void sessionPort.exit(sessionToken);
    onExit();
  };

  if (sessionError !== null) {
    return <main className="grp-loading"><strong>게임을 시작할 수 없습니다</strong><p>{sessionError}</p><button onClick={onExit} type="button">돌아가기</button></main>;
  }

  return (
    <main className="grp-root" data-game-studio-runtime="reference">
      <header className="grp-topbar">
        <div><span className="grp-brand">F</span><strong>{project.title}</strong><em>{mode === 'PREVIEW' ? 'PLAY TEST' : 'FESTA GAME'}</em></div>
        <div><span>Scene</span><strong>{scene?.name ?? runtime.session.currentSceneId}</strong></div>
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
            {scene.tileLayers.map((layer) => (
              <div className="grp-tile-layer" key={layer.id}>
                {layer.data.map((tile, index) => tile < 0 ? null : (
                  <span
                    className={`is-tile-${tile % 8}`}
                    key={`${layer.id}-${index}`}
                    style={{
                      gridColumn: (index % scene.width) + 1,
                      gridRow: Math.floor(index / scene.width) + 1,
                      ...(() => {
                        const visual = resolveTilesetVisual(
                          project.assets.find((asset) => asset.id === layer.tilesetAssetId),
                          assetUrls,
                        );
                        return visual === null ? {} : tileBackgroundStyle(visual, tile);
                      })(),
                    }}
                  />
                ))}
              </div>
            ))}
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
          <section className="grp-result is-error"><span>!</span><h1>게임 실행 오류</h1><p>{runtime.session.failure?.message}</p><button onClick={exit} type="button">편집기로 돌아가기</button></section>
        )}
      </section>

      <aside className="grp-hud">
        <div className="grp-player-stats"><span>상태</span><strong>♥ {runtime.playerHealth} / {runtime.maxPlayerHealth}</strong><strong>★ {runtime.score.toLocaleString('ko-KR')}점</strong></div>
        <div><span>INVENTORY</span>{inventory.length === 0 ? <small>비어 있음</small> : inventory.map((item) => <strong key={item}>◇ {item}</strong>)}</div>
        <div className="grp-controls"><span>{scene?.type === 'PLATFORMER' ? '이동 / 점프' : '이동'}</span><div><button onClick={() => setRuntime((current) => moveReferencePlayer(project, current, 'UP'))} type="button">↑</button><button onClick={() => setRuntime((current) => moveReferencePlayer(project, current, 'LEFT'))} type="button">←</button><button onClick={() => setRuntime((current) => moveReferencePlayer(project, current, 'DOWN'))} type="button">↓</button><button onClick={() => setRuntime((current) => moveReferencePlayer(project, current, 'RIGHT'))} type="button">→</button></div></div>
        <button className="grp-interact" disabled={activeDialogue !== null} onClick={() => setRuntime((current) => interactReferencePlayer(project, current))} type="button"><kbd>E</kbd> 상호작용</button>
        {canShoot && <button className="grp-shoot" disabled={activeDialogue !== null} onClick={() => setRuntime((current) => shootReferenceProjectile(project, current))} type="button"><kbd>F</kbd> 발사</button>}
      </aside>
    </main>
  );
};
