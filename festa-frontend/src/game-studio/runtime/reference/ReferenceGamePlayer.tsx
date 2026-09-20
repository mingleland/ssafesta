import { Fragment, memo, useEffect, useMemo, useRef, useState } from 'react';
import { DEFAULT_GAME_RULES, findScene, type AssetReference, type GameObjective, type GameProject, type TileLayer } from '../../contracts/gameProject.ts';
import { getActiveDialogue, getAvailableDialogueChoices } from '../dialogue/dialogueRunner.ts';
import { findBuiltinSpriteSheet } from '../../studio/assets/builtinAssetCatalog.ts';
import { findPresetDefinition } from '../../studio/model/authoringRegistry.ts';
import { SpriteAnimationPreview } from '../../studio/ui/SpriteAnimationPreview.tsx';
import { resolveTilesetVisual, tileBackgroundStyle } from '../../studio/assets/tilesetVisual.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle, staticImagePortraitStyle } from '../../studio/assets/staticImageVisual.ts';
import type { GameSessionPort } from '../ports/gameSessionPort.ts';
import { summarizeFramePerformance, type FramePerformanceSummary } from './framePerformance.ts';
import {
  chooseReferenceDialogue,
  currentInteractionTarget,
  interactReferencePlayer,
  movePlayerFromHeldKeys,
  objectiveProgress,
  planCatchUpTicks,
  REFERENCE_TICK_MS,
  shootReferenceProjectile,
  startReferenceRuntime,
  tickReferenceWorld,
  type MoveDirection,
  type ReferenceRuntimeState,
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

// S15P21A604-526 — 캐릭터 위 "-N" 피해 표시가 떠 있는 시간. ReferenceGamePlayer.css의
// grp-damage-popup 애니메이션 duration과 반드시 같은 값이어야 한다(자바스크립트 타이머가
// 실제로 요소를 지우는 시점 = CSS 애니메이션이 끝나 보이지 않게 되는 시점).
const DAMAGE_POPUP_DURATION_MS = 900;

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
  // S15P21A604-526 — 체력이 깎여도 상단 수치 텍스트만 바뀌고 캐릭터 쪽에는 아무 피드백이
  // 없던 것을, runtime.lastDamageTick 변화를 감지해 캐릭터 위에 "-N"을 잠깐 띄웠다 서서히
  // 사라지게(DAMAGE_POPUP_DURATION_MS 뒤 자동 제거) 한다. 이 표시 자체는 게임 판정에
  // 영향이 없는 순수 시각 효과라 굳이 runtime state(순수 함수 리듀서)에 넣지 않고 컴포넌트
  // 로컬 state로 둔다.
  const [damagePopups, setDamagePopups] = useState<readonly { readonly id: number; readonly amount: number }[]>([]);
  const damagePopupIdRef = useRef(0);
  const lastDamageTickRef = useRef(runtime.lastDamageTick);
  const [sessionToken, setSessionToken] = useState<string | null>(null);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [framePerformance, setFramePerformance] = useState<FramePerformanceSummary | null>(null);
  const [performanceTabActive, setPerformanceTabActive] = useState(() => (
    typeof document === 'undefined' || document.visibilityState === 'visible'
  ));
  const completionReported = useRef(false);
  // 현재 눌려 있는 방향키 집합 — keydown/keyup으로만 갱신하고, 실제 이동은 tick 이펙트가
  // 매 REFERENCE_TICK_MS(120ms)마다 이 집합을 읽어 적용한다(S15P21A604-363).
  const pressedDirectionsRef = useRef<Set<MoveDirection>>(new Set());
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
  // S15P21A604-532 — 하단 고정 "E 상호작용" 버튼과 정확히 같은 판정(currentInteractionTarget)
  // 으로 오브젝트 위 안내 문구를 띄운다 — 둘이 다른 로직을 쓰면 "버튼은 눌리는데 힌트가
  // 안 뜨는" 불일치가 생긴다. 대화가 진행 중일 때는(activeDialogue !== null) 기존 버튼도
  // disabled되므로 힌트도 같이 숨긴다.
  const interactionTarget = activeDialogue === null ? currentInteractionTarget(project, runtime) : null;
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

  // S15P21A604-526 — lastDamageTick이 이전에 본 값과 달라졌을 때만(=실제로 새 피해가
  // 적용됐을 때만) 표시를 하나 추가한다. 무적 시간 중 damagePlayer가 조기 반환되는 호출은
  // referenceRuntime.ts 쪽에서 애초에 이 값을 갱신하지 않으므로 여기서 따로 걸러낼 필요가
  // 없다 — tick이 그대로면 이 effect도 아무 일도 하지 않는다.
  useEffect(() => {
    if (runtime.lastDamageAmount === null || runtime.lastDamageTick === lastDamageTickRef.current) return;
    lastDamageTickRef.current = runtime.lastDamageTick;
    const id = damagePopupIdRef.current + 1;
    damagePopupIdRef.current = id;
    const amount = runtime.lastDamageAmount;
    setDamagePopups((current) => [...current, { id, amount }]);
    const timeoutId = window.setTimeout(() => {
      setDamagePopups((current) => current.filter((popup) => popup.id !== id));
    }, DAMAGE_POPUP_DURATION_MS);
    return () => window.clearTimeout(timeoutId);
  }, [runtime.lastDamageAmount, runtime.lastDamageTick]);

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
      // S15P21A604-490 — 대화창(Overlay/Fullscreen 공용)이 떠 있을 때는 숫자키(1~6, 계약상
      // 선택지 최대 개수)로 마우스 클릭과 동일하게 선택지를 고를 수 있다. 존재하지 않는
      // 번호는 무시한다. OS auto-repeat(키를 계속 누르고 있으면 반복 발생하는 keydown)은
      // 무시해 최초 입력에만 반응한다 — 선택으로 다음 노드가 떠서 같은 번호에 다른 선택지가
      // 있으면 의도치 않게 연달아 고르게 되는 것을 막기 위함이다. NumLock이 꺼진 numpad는
      // 지원하지 않는다(팀 결정, event.key 기반).
      if (activeDialogue !== null && !event.repeat && /^[1-6]$/.test(event.key)) {
        const choice = choices[Number(event.key) - 1];
        if (choice !== undefined) {
          event.preventDefault();
          setRuntime((current) => chooseReferenceDialogue(project, current, choice.id));
        }
        return;
      }
      const direction = keyDirection(event.key);
      if (direction !== null) {
        event.preventDefault();
        // 이미 눌려 있는 키의 OS auto-repeat keydown은 여기서 걸러진다 — "멈췄다가 급발진"의
        // 원인이던 auto-repeat 타이밍이 이동에 영향을 주지 않는다. 처음 눌린 순간에만 즉시
        // 한 걸음 이동해 짧게 탭 했을 때의 반응성은 그대로 유지하고, 계속 누르고 있는 동안은
        // 아래 tick 이펙트가 pressedDirectionsRef를 읽어 120ms마다 균일하게 이동시킨다.
        if (!pressedDirectionsRef.current.has(direction)) {
          pressedDirectionsRef.current.add(direction);
          setRuntime((current) => movePlayerFromHeldKeys(project, current, pressedDirectionsRef.current));
        }
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
    const onKeyUp = (event: KeyboardEvent) => {
      const direction = keyDirection(event.key);
      if (direction !== null) pressedDirectionsRef.current.delete(direction);
    };
    // 키를 누른 채로 창(탭)이 포커스를 잃으면 브라우저가 keyup을 보내지 않을 수 있다 —
    // 방향키가 "눌린 채로 끼는" 것을 막기 위해 포커스를 잃으면 집합을 비운다.
    const onBlur = () => pressedDirectionsRef.current.clear();
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    window.addEventListener('blur', onBlur);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('keyup', onKeyUp);
      window.removeEventListener('blur', onBlur);
    };
  }, [activeDialogue, project]);

  useEffect(() => {
    if (scene === undefined || scene.type === 'DIALOGUE' || activeDialogue !== null || runtime.session.status !== 'PLAYING') return undefined;
    // S15P21A604-363 — 탭이 백그라운드/비활성이면 브라우저가 이 setInterval 자체의 발화
    // 주기를 초당 1회 수준까지 강제로 늦출 수 있다(브라우저 정책이라 우리가 막을 수 없음).
    // 그래서 "콜백 한 번 = tick 한 번"으로 고정하지 않고, 콜백이 실제로 불릴 때마다
    // performance.now()로 지난 실시간을 재서 그만큼의 논리 tick을 몰아 처리한다 — 늦게
    // 불렸어도 tick 하나의 실제 길이는 항상 REFERENCE_TICK_MS에 가깝게 유지되고, 그 위에
    // 얹힌 무적시간(REFERENCE_TICK_MS 기준 tick 수) 같은 계산도 다시 정확해진다.
    // 아주 오래(수 분) 비활성이었다면 밀린 tick을 전부 몰아치지 않고 MAX_CATCHUP_TICKS까지만
    // 처리하고 나머지 밀린 시간은 버린다(급증 스폰 등 부작용 방지) — 그만큼은 현재 시각으로
    // 재동기화해 다음 콜백부터 다시 정상 페이스로 이어간다.
    let lastTickAt = performance.now();
    const timer = window.setInterval(() => {
      const now = performance.now();
      const { steps, consumedMs } = planCatchUpTicks(now - lastTickAt);
      if (steps <= 0) return;
      lastTickAt += consumedMs;
      setRuntime((current) => {
        let next: ReferenceRuntimeState = current;
        for (let step = 0; step < steps; step += 1) {
          next = tickReferenceWorld(project, movePlayerFromHeldKeys(project, next, pressedDirectionsRef.current));
        }
        return next;
      });
    }, REFERENCE_TICK_MS);
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
              // S15P21A604-492 — CSS의 width: 100%(고정값)만으로는 세로가 긴 씬에서 비율이
              // 깨진다: aspect-ratio로 계산된 높이가 세로 제한을 넘으면 높이는 잘리지만,
              // width가 이미 고정값이라 폭이 다시 계산되지 않는다(스펙상 aspect-ratio는
              // auto인 쪽만 유도한다). 그래서 "가로 제한"과 "세로 제한을 씬 비율로 역산한
              // 폭" 중 작은 쪽을 직접 계산해 항상 비율이 유지되게 한다.
              // S15P21A604-542 — 대화형 씬(.grp-story-backdrop, 92%×82%)에 비해 맵형 씬이
              // 화면과 무관한 고정 1120px 가로 상한 때문에 훨씬 작게 떠 화면 전환이
              // 불연속적으로 느껴졌다. 가로 상한을 화면 크기 기준(100vw - .grp-stage-wrap
              // 좌우 padding 34px×2)의 92%로 바꿔 큰 화면에서 훨씬 커지게 한다.
              // 세로 쪽 (100vh - 130px)에는 처음에 여기도 .92를 곱했었는데, 이 값 자체가
              // 이미 "넘치지 않는 최대치"였던 걸 다시 92%로 줄이는 꼴이라 세로 제한이
              // 걸리는 씬(정사각형에 가깝거나 세로가 긴 씬)은 오히려 이전보다 작아지는
              // 회귀가 나서(육안 확인으로 발견) 뺐다 — 가로만 92%를 곱하는 게 맞다.
              width: `min(calc((100vw - 68px) * .92), calc((100vh - 130px) * ${scene.width} / ${scene.height}))`,
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
              const objectPercentPosition = {
                left: `${((runtimePosition.x + .5) / scene.width) * 100}%`,
                top: `${((runtimePosition.y + .5) / scene.height) * 100}%`,
              };
              return (
                <Fragment key={object.id}>
                  {/* S15P21A604-535 — 이미지 asset이 있으면(has-visual) 어두운 배지 박스를
                      완전히 빼서 에디터 캔버스(.gss-map-object, 기본 투명)와 같아지게 한다.
                      이미지가 없어 이모지 폴백(definition.icon)만 뜨는 경우는 그 배지가
                      가독성에 필요해 기존 스타일을 유지한다(ReferenceGamePlayer.css의
                      .grp-object:not(.has-visual) 참고). */}
                  <span
                    className={`grp-object grp-object--${object.preset.toLowerCase()}${spriteVisual === null ? '' : ' has-visual'}`}
                    style={{
                      ...objectPercentPosition,
                      transform: `translate(-50%,-50%) scale(${sprite?.type === 'SPRITE' ? (sprite.scale ?? 100) / 100 : 1})`,
                      zIndex: sprite?.type === 'SPRITE' ? 10 + (sprite.zIndex ?? 2) : 12,
                    }}
                    title={definition.label}
                  >{spriteVisual === null ? definition.icon : <span className="grp-static-sprite" style={staticImageBackgroundStyle(spriteVisual)} />}</span>
                  {/* S15P21A604-529 — 이름이 있고("" 포함 빈 이름은 미표시) "플레이 중 표시"가
                      켜진 오브젝트만, 오브젝트와 같은 좌표에서 위로 오프셋한 상시 이름표를
                      그린다(-526의 순간 페이드아웃 표시와 달리 계속 떠 있음 — 별도 타이머
                      없이 매 렌더마다 조건만 확인). PLAYER_SPAWN은 이 filter에서 이미
                      제외되고 계약상 name/showNameInPlay 자체를 가질 수 없다. */}
                  {object.showNameInPlay === true && object.name !== undefined && object.name !== '' && (
                    <span className="grp-object-nameplate" style={objectPercentPosition}>{object.name}</span>
                  )}
                  {/* S15P21A604-532 — 지금 상호작용 범위 안의 대상이면서 INTERACTABLE
                      컴포넌트에 안내 문구가 있을 때만 오브젝트 "위"에 힌트를 띄운다(이름표는
                      아래라 서로 안 겹친다). ON_INTERACT 이벤트로만 상호작용 가능하고
                      INTERACTABLE 컴포넌트가 없는 오브젝트는 보여줄 문구 자체가 없어 표시하지
                      않는다 — 하단 고정 "E 상호작용" 버튼은 이 조건과 무관하게 그대로 둔다. */}
                  {interactionTarget?.id === object.id && (() => {
                    const interactable = object.components.find((component) => component.type === 'INTERACTABLE');
                    return interactable?.type === 'INTERACTABLE' ? (
                      <span className="grp-interaction-hint" style={objectPercentPosition}>{interactable.prompt}</span>
                    ) : null;
                  })()}
                </Fragment>
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
            {/* S15P21A604-526 — 캐릭터와 같은 좌표에 놓고 CSS(grp-damage-popup)로 오른쪽
                위 오프셋 + 위로 이동하며 페이드아웃하는 애니메이션을 준다. 피격 시점의
                정확한 위치가 아니라 "지금" 위치를 쓴다 — 체력이 0이 되어 즉시 체크포인트로
                순간이동하는 마지막 피격은 표시가 새 위치에서 뜨지만, 표시 자체가 아주
                짧게(900ms) 스쳐 지나가는 연출이라 문제되지 않는다고 판단했다. */}
            {damagePopups.map((popup) => (
              <span
                className="grp-damage-popup"
                key={popup.id}
                style={{
                  left: `${((runtime.playerPosition!.x + .5) / scene.width) * 100}%`,
                  top: `${((runtime.playerPosition!.y + .5) / scene.height) * 100}%`,
                }}
              >-{popup.amount}</span>
            ))}

            {/* S15P21A604-540 — 오른쪽 사이드바를 없애고 상태/목표/인벤토리/조작 안내를
                게임 캔버스(.grp-map) 안쪽 오버레이로 옮긴다. 목표 바는 기존과 동일한 조건
                (objectives 없으면 미표시)으로 상단에, 상태(하트/점수)는 우측 상단, 인벤토리는
                좌측 상단, 상호작용/발사 안내는 좌측 하단에 둔다. */}
            {completionRules.objectives.length > 0 && (
              <div className="grp-hud-objectives">
                <span>게임 목표 · {completionRules.mode === 'ALL' ? '모두 달성' : '하나 달성'}</span>
                {completionRules.objectives.map((objective) => {
                  const progress = objectiveProgress(runtime, objective);
                  const completed = progress >= objective.target;
                  return <strong className={completed ? 'is-complete' : ''} key={objective.type}><i>{completed ? '✓' : '○'}</i>{objectiveCopy(objective)}<small>{Math.min(progress, objective.target).toLocaleString('ko-KR')} / {objective.target.toLocaleString('ko-KR')}</small></strong>;
                })}
              </div>
            )}
            <div className="grp-hud-status">
              <div aria-label={`체력 ${runtime.playerHealth} / ${runtime.maxPlayerHealth}`} className="grp-hud-hearts">
                {/* 깎이면 왼쪽부터 사라진다(QA 확정) — 남은 체력만큼 "오른쪽" 하트가
                    채워진 상태로 남고, 왼쪽(index가 작은 쪽)부터 빈 하트가 된다. */}
                {Array.from({ length: runtime.maxPlayerHealth }, (_, index) => (
                  <span className={index >= runtime.maxPlayerHealth - runtime.playerHealth ? 'is-filled' : ''} key={index}>♥</span>
                ))}
              </div>
              <strong className="grp-hud-score">★ {runtime.score.toLocaleString('ko-KR')}</strong>
            </div>
            <div className="grp-hud-inventory">
              <span>INVENTORY</span>
              {inventory.length === 0 ? <small>비어 있음</small> : inventory.map((item) => <strong key={item}>◇ {item}</strong>)}
            </div>
            <div className="grp-hud-actions">
              <span className="grp-action-label">E 상호작용</span>
              {canShoot && <span className="grp-action-label">F 발사</span>}
            </div>
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
            {dialoguePortrait !== null && <div className="grp-dialogue-portrait" style={staticImagePortraitStyle(dialoguePortrait)} />}
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
    </main>
  );
};
