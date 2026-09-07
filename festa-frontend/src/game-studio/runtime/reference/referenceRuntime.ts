import { DEFAULT_GAME_RULES, findScene, type GameObject, type GameObjective, type GameProject, type Position2d } from '../../contracts/gameProject.ts';
import { dispatchTrigger, startRuntimeSession } from '../../core/runEvent.ts';
import { failRuntimeSession, type RuntimeSessionState } from '../../core/runtimeState.ts';
import { chooseDialogueChoice } from '../dialogue/dialogueRunner.ts';

export type MoveDirection = 'UP' | 'DOWN' | 'LEFT' | 'RIGHT';

export interface RuntimeProjectile {
  readonly id: string;
  readonly position: Position2d;
  readonly direction: 1 | -1;
  readonly assetId: string;
  readonly damage: number;
  readonly owner: 'PLAYER' | 'ENEMY';
}

export interface RuntimeEnemy {
  readonly id: string;
  readonly position: Position2d;
  readonly assetId: string;
  readonly health: number;
}

export interface ReferenceRuntimeState {
  readonly session: RuntimeSessionState;
  readonly playerPosition: Position2d | null;
  readonly facing: MoveDirection;
  readonly lastInteractionTargetId: string | null;
  readonly verticalVelocity: number;
  // S15P21A604-408 — 마리오 스타일 가변 점프의 "홀드 유지 틱 수"(0이면 점프 중 아님).
  // UP이 계속 눌려 있는 동안(최대 JUMP_HOLD_MAX_TICKS까지) 이 값이 매 틱 늘어나며 그만큼
  // 계속 상승시킨다 — 놓거나 상한에 닿으면 0으로 리셋되고 tickReferenceWorld의 기존 중력
  // 감속 하강이 이어받는다.
  readonly jumpHoldTicks: number;
  readonly playerHealth: number;
  readonly maxPlayerHealth: number;
  readonly invulnerableUntilTick: number;
  readonly score: number;
  readonly defeatedEnemies: number;
  readonly elapsedMs: number;
  readonly checkpointPosition: Position2d | null;
  readonly objectPositions: Readonly<Record<string, Position2d>>;
  readonly objectDirections: Readonly<Record<string, 1 | -1>>;
  readonly tickCount: number;
  readonly objectHealth: Readonly<Record<string, number>>;
  readonly projectiles: readonly RuntimeProjectile[];
  readonly spawnedEnemies: readonly RuntimeEnemy[];
  readonly nextEntityId: number;
  readonly lastPlayerShotTick: number;
}

export const REFERENCE_TICK_MS = 120;

// S15P21A604-363 — 탭이 백그라운드/비활성 상태가 되면 브라우저가 setInterval을 초당 1회
// 수준까지 강제로 늦춘다(정책이라 우리가 없앨 수 없음). 그 상태로 방향키를 계속 누르고
// 있으면 tick 자체가 실제로는 ~1초에 한 번만 도는데, 사망 무적시간(REFERENCE_TICK_MS 기준
// 8틱 ≈ 0.96초로 설계)이 그 늦어진 tick 단위 그대로 계산되면서 실측 무적시간이 8배 가까이
// 늘어나 함정을 반복해서 무해하게 통과하는 버그로 이어졌다(문서: docs/25_트러블슈팅.md 예정).
// 고치는 방법은 tick을 고정 setInterval 카운트가 아니라 performance.now() 기준 경과-실시간
// 누적기로 돌려서, 늦게 발화한 콜백 안에서 밀린 만큼 여러 논리 tick을 몰아 처리하는 것이다
// (ReferenceGamePlayer.tsx의 tick 이펙트 참고). 다만 아주 오래 백그라운드에 있었다면(수 분)
// 그만큼을 전부 몰아치면 스포너가 몬스터를 대량 생성하는 등 부작용이 생기므로, 한 콜백에서
// 처리할 논리 tick 수를 이 값으로 캡하고 남는 경과시간은 버린다(현재 시각으로 재동기화).
export const MAX_CATCHUP_TICKS = 8;

export interface CatchUpPlan {
  // 이번에 몰아 처리할 논리 tick 수(0이면 아직 한 tick도 안 지남).
  readonly steps: number;
  // lastTickAt(기준 시각)에 더해야 할 값. 정상 범위(steps <= maxSteps)면 처리하지 못한
  // 나머지 단수(< tickMs)는 버리지 않고 다음 호출로 이월시켜 드리프트를 막는다. 캡을
  // 넘겨 밀렸던 경우엔 elapsedMs 전체를 반환해 남은 밀린 시간을 버리고 현재 시각으로
  // 완전히 재동기화한다.
  readonly consumedMs: number;
}

// 마지막 처리 이후 실제로 흐른 시간(elapsedMs)을 tickMs 단위 논리 tick 수로 환산한다.
// ReferenceGamePlayer의 tick 이펙트가 이 계산 자체를 갖고 있으면 실제 setInterval 발화
// 간격(브라우저 스로틀링 등)에 테스트가 종속되므로, 순수 함수로 뽑아 독립적으로 검증한다.
export const planCatchUpTicks = (
  elapsedMs: number,
  tickMs: number = REFERENCE_TICK_MS,
  maxSteps: number = MAX_CATCHUP_TICKS,
): CatchUpPlan => {
  const impliedSteps = Math.floor(elapsedMs / tickMs);
  if (impliedSteps <= 0) return { steps: 0, consumedMs: 0 };
  if (impliedSteps > maxSteps) return { steps: maxSteps, consumedMs: elapsedMs };
  return { steps: impliedSteps, consumedMs: impliedSteps * tickMs };
};

export const objectiveProgress = (
  state: Pick<ReferenceRuntimeState, 'score' | 'defeatedEnemies' | 'elapsedMs'>,
  objective: GameObjective,
): number => {
  if (objective.type === 'SCORE_AT_LEAST') return state.score;
  if (objective.type === 'DEFEAT_ENEMIES') return state.defeatedEnemies;
  return Math.floor(state.elapsedMs / 1000);
};

const applyCompletionRules = (project: GameProject, state: ReferenceRuntimeState): ReferenceRuntimeState => {
  if (state.session.status !== 'PLAYING') return state;
  const completion = (project.rules ?? DEFAULT_GAME_RULES).completion;
  if (completion.objectives.length === 0) return state;
  const results = completion.objectives.map((objective) => objectiveProgress(state, objective) >= objective.target);
  const completed = completion.mode === 'ALL' ? results.every(Boolean) : results.some(Boolean);
  return completed ? { ...state, session: { ...state.session, status: 'COMPLETED' } } : state;
};

const directionDelta: Readonly<Record<MoveDirection, Position2d>> = {
  UP: { x: 0, y: -1 },
  DOWN: { x: 0, y: 1 },
  LEFT: { x: -1, y: 0 },
  RIGHT: { x: 1, y: 0 },
};

const topDownSpawn = (project: GameProject, sceneId: string): Position2d | null => {
  const scene = findScene(project, sceneId);
  if (scene === undefined || scene.type === 'DIALOGUE') return null;
  return scene.objects.find((object) => object.preset === 'PLAYER_SPAWN')?.position ?? null;
};

const syncAfterSessionChange = (
  project: GameProject,
  previous: ReferenceRuntimeState,
  session: RuntimeSessionState,
): ReferenceRuntimeState => {
  const changedScene = session.currentSceneId !== previous.session.currentSceneId;
  const spawn = topDownSpawn(project, session.currentSceneId);
  return {
    ...previous,
    session,
    playerPosition: changedScene ? spawn : previous.playerPosition,
    checkpointPosition: changedScene ? spawn : previous.checkpointPosition,
    verticalVelocity: changedScene ? 0 : previous.verticalVelocity,
    jumpHoldTicks: changedScene ? 0 : previous.jumpHoldTicks,
    projectiles: changedScene ? [] : previous.projectiles,
    spawnedEnemies: changedScene ? [] : previous.spawnedEnemies,
  };
};

export const startReferenceRuntime = (project: GameProject): ReferenceRuntimeState => {
  const session = startRuntimeSession(project);
  return {
    session,
    playerPosition: topDownSpawn(project, session.currentSceneId),
    facing: 'DOWN',
    lastInteractionTargetId: null,
    verticalVelocity: 0,
    jumpHoldTicks: 0,
    playerHealth: 3,
    maxPlayerHealth: 3,
    invulnerableUntilTick: -1,
    score: 0,
    defeatedEnemies: 0,
    elapsedMs: 0,
    checkpointPosition: topDownSpawn(project, session.currentSceneId),
    objectPositions: Object.fromEntries(project.scenes.flatMap((scene) => (
      scene.type === 'DIALOGUE' ? [] : scene.objects.map((object) => [object.id, object.position] as const)
    ))),
    objectDirections: Object.fromEntries(project.scenes.flatMap((scene) => (
      scene.type === 'DIALOGUE' ? [] : scene.objects.map((object) => [object.id, 1 as const] as const)
    ))),
    tickCount: 0,
    objectHealth: Object.fromEntries(project.scenes.flatMap((scene) => (
      scene.type === 'DIALOGUE' ? [] : scene.objects.flatMap((object) => {
        const health = object.components.find((component) => component.type === 'HEALTH');
        return health?.type === 'HEALTH' ? [[object.id, health.max] as const] : [];
      })
    ))),
    projectiles: [],
    spawnedEnemies: [],
    nextEntityId: 1,
    lastPlayerShotTick: -1000,
  };
};

const isVisible = (state: ReferenceRuntimeState, object: GameObject): boolean => (
  state.session.objectVisibility[object.id] !== false
);

const isSolid = (object: GameObject): boolean => object.components.some((component) => (
  component.type === 'COLLIDER' && component.solid
));

const visibleOccupantsAt = (
  state: ReferenceRuntimeState,
  objects: readonly GameObject[],
  position: Position2d,
): readonly GameObject[] => objects.filter((object) => (
  object.preset !== 'PLAYER_SPAWN'
  && (state.objectPositions[object.id] ?? object.position).x === Math.round(position.x)
  && (state.objectPositions[object.id] ?? object.position).y === Math.round(position.y)
  && isVisible(state, object)
));

const damagePlayer = (
  project: GameProject,
  state: ReferenceRuntimeState,
  amount: number,
): ReferenceRuntimeState => {
  if (state.tickCount < state.invulnerableUntilTick) return state;
  const remaining = state.playerHealth - amount;
  if (remaining > 0) return { ...state, playerHealth: remaining, invulnerableUntilTick: state.tickCount + 8 };
  if ((project.rules ?? DEFAULT_GAME_RULES).playerDefeat === 'END_GAME') {
    return {
      ...state,
      playerHealth: 0,
      session: failRuntimeSession(state.session, {
        code: 'PLAYER_DEFEATED',
        message: '체력이 모두 소진되었습니다. 다시 도전해 보세요.',
      }),
    };
  }
  return {
    ...state,
    playerHealth: state.maxPlayerHealth,
    playerPosition: state.checkpointPosition ?? topDownSpawn(project, state.session.currentSceneId),
    verticalVelocity: 0,
    jumpHoldTicks: 0,
    invulnerableUntilTick: state.tickCount + 8,
  };
};

const applyContactComponents = (
  project: GameProject,
  state: ReferenceRuntimeState,
  object: GameObject,
): ReferenceRuntimeState => {
  let next = state;
  const checkpoint = object.components.some((component) => component.type === 'CHECKPOINT');
  if (checkpoint) next = { ...next, checkpointPosition: next.objectPositions[object.id] ?? object.position };
  const scoreValue = object.components.find((component) => component.type === 'SCORE_VALUE');
  if (scoreValue?.type === 'SCORE_VALUE' && isVisible(next, object)) {
    next = {
      ...next,
      score: next.score + scoreValue.value,
      session: {
        ...next.session,
        objectVisibility: { ...next.session.objectVisibility, [object.id]: false },
      },
    };
  }
  const damage = object.components.find((component) => component.type === 'DAMAGE');
  if (damage?.type === 'DAMAGE') {
    next = damagePlayer(project, next, damage.amount);
  }
  return next;
};

export const shootReferenceProjectile = (
  project: GameProject,
  state: ReferenceRuntimeState,
): ReferenceRuntimeState => {
  if (state.session.status !== 'PLAYING' || state.session.activeDialogueSceneId !== null || state.playerPosition === null) return state;
  const scene = findScene(project, state.session.currentSceneId);
  if (scene === undefined || scene.type === 'DIALOGUE') return state;
  const shooter = scene.objects
    .find((object) => object.preset === 'PLAYER_SPAWN')
    ?.components.find((component) => component.type === 'SHOOTER');
  if (shooter?.type !== 'SHOOTER') return state;
  const cooldownTicks = Math.max(1, Math.ceil(shooter.cooldownMs / 120));
  if (state.tickCount - state.lastPlayerShotTick < cooldownTicks) return state;
  const direction: 1 | -1 = state.facing === 'LEFT' ? -1 : 1;
  return {
    ...state,
    lastPlayerShotTick: state.tickCount,
    nextEntityId: state.nextEntityId + 1,
    projectiles: [...state.projectiles, {
      id: `projectile${state.nextEntityId}`,
      position: { x: state.playerPosition.x + direction, y: state.playerPosition.y },
      direction,
      assetId: shooter.projectileAssetId,
      damage: shooter.damage,
      owner: 'PLAYER',
    }],
  };
};

const enterPosition = (
  project: GameProject,
  state: ReferenceRuntimeState,
  position: Position2d,
): ReferenceRuntimeState => {
  const scene = findScene(project, state.session.currentSceneId);
  if (scene === undefined || scene.type === 'DIALOGUE') return state;
  let moved: ReferenceRuntimeState = { ...state, playerPosition: position };
  for (const object of visibleOccupantsAt(moved, scene.objects, position)) {
    const eventSession = dispatchTrigger(project, moved.session, { type: 'ON_ENTER', targetId: object.id });
    const contacted = applyContactComponents(project, moved, object);
    const mergedVisibility = { ...moved.session.objectVisibility };
    for (const [objectId, visible] of Object.entries(eventSession.objectVisibility)) {
      if (visible !== moved.session.objectVisibility[objectId]) mergedVisibility[objectId] = visible;
    }
    for (const [objectId, visible] of Object.entries(contacted.session.objectVisibility)) {
      if (visible !== moved.session.objectVisibility[objectId]) mergedVisibility[objectId] = visible;
    }
    const session: RuntimeSessionState = contacted.session.status === 'FAILED'
      ? {
          ...eventSession,
          status: 'FAILED',
          activeDialogueSceneId: null,
          currentDialogueNodeId: null,
          failure: contacted.session.failure,
          objectVisibility: mergedVisibility,
        }
      : {
          ...eventSession,
          objectVisibility: mergedVisibility,
        };
    moved = syncAfterSessionChange(project, contacted, session);
    if (session.status !== 'PLAYING' || session.currentSceneId !== scene.id || session.activeDialogueSceneId !== null) break;
  }
  return applyCompletionRules(project, moved);
};

const isPlatformerGrounded = (
  state: ReferenceRuntimeState,
  scene: Extract<GameProject['scenes'][number], { type: 'PLATFORMER' }>,
): boolean => {
  if (state.playerPosition === null) return false;
  return visibleOccupantsAt(state, scene.objects, {
    x: state.playerPosition.x,
    y: state.playerPosition.y + 1,
  }).some(isSolid);
};

export const tickReferenceWorld = (
  project: GameProject,
  state: ReferenceRuntimeState,
): ReferenceRuntimeState => {
  if (state.session.status !== 'PLAYING' || state.session.activeDialogueSceneId !== null || state.playerPosition === null) return state;
  const scene = findScene(project, state.session.currentSceneId);
  if (scene === undefined || scene.type === 'DIALOGUE') return state;
  let nextState: ReferenceRuntimeState = {
    ...state,
    tickCount: state.tickCount + 1,
    elapsedMs: state.elapsedMs + REFERENCE_TICK_MS,
  };
  for (const object of scene.objects) {
    const movement = object.components.find((component) => component.type === 'AUTO_MOVE');
    if (movement?.type !== 'AUTO_MOVE') continue;
    const everyTicks = Math.max(1, Math.round(10 / movement.speed));
    if (nextState.tickCount % everyTicks !== 0) continue;
    const current = nextState.objectPositions[object.id] ?? object.position;
    const direction = nextState.objectDirections[object.id] ?? 1;
    const moved = movement.axis === 'HORIZONTAL'
      ? { x: current.x + direction, y: current.y }
      : { x: current.x, y: current.y + direction };
    const originDelta = movement.axis === 'HORIZONTAL' ? moved.x - object.position.x : moved.y - object.position.y;
    const outside = Math.abs(originDelta) > movement.range || moved.x < 0 || moved.y < 0 || moved.x >= scene.width || moved.y >= scene.height;
    if (outside) {
      nextState = { ...nextState, objectDirections: { ...nextState.objectDirections, [object.id]: direction === 1 ? -1 : 1 } };
    } else {
      nextState = { ...nextState, objectPositions: { ...nextState.objectPositions, [object.id]: moved } };
    }
  }
  let spawnedEnemies = [...nextState.spawnedEnemies];
  let projectiles = [...nextState.projectiles];
  let nextEntityId = nextState.nextEntityId;
  for (const object of scene.objects) {
    const position = nextState.objectPositions[object.id] ?? object.position;
    const spawner = object.components.find((component) => component.type === 'SPAWNER');
    if (spawner?.type === 'SPAWNER') {
      const intervalTicks = Math.max(2, Math.round(spawner.intervalMs / 120));
      if (nextState.tickCount % intervalTicks === 0 && spawnedEnemies.length < spawner.maxAlive) {
        spawnedEnemies.push({ id: `enemy${nextEntityId}`, position: { x: position.x + 1, y: position.y }, assetId: spawner.enemyAssetId, health: 3 });
        nextEntityId += 1;
      }
    }
    const shooter = object.components.find((component) => component.type === 'SHOOTER');
    if (object.preset !== 'PLAYER_SPAWN' && shooter?.type === 'SHOOTER') {
      const intervalTicks = Math.max(2, Math.round(shooter.cooldownMs / 120));
      if (nextState.tickCount % intervalTicks === 0 && nextState.playerPosition !== null) {
        const direction: 1 | -1 = nextState.playerPosition.x < position.x ? -1 : 1;
        projectiles.push({ id: `projectile${nextEntityId}`, position: { x: position.x + direction, y: position.y }, direction, assetId: shooter.projectileAssetId, damage: shooter.damage, owner: 'ENEMY' });
        nextEntityId += 1;
      }
    }
  }
  if (nextState.tickCount % 4 === 0) {
    spawnedEnemies = spawnedEnemies.map((enemy) => {
      if (nextState.playerPosition === null) return enemy;
      const delta = nextState.playerPosition.x === enemy.position.x ? 0 : nextState.playerPosition.x < enemy.position.x ? -1 : 1;
      return { ...enemy, position: { x: Math.max(0, Math.min(scene.width - 1, enemy.position.x + delta)), y: enemy.position.y } };
    });
  }
  const survivingProjectiles: RuntimeProjectile[] = [];
  for (const projectile of projectiles) {
    const moved = { ...projectile, position: { x: projectile.position.x + projectile.direction, y: projectile.position.y } };
    if (moved.position.x < 0 || moved.position.x >= scene.width) continue;
    if (moved.owner === 'ENEMY' && nextState.playerPosition !== null && nextState.playerPosition.x === moved.position.x && nextState.playerPosition.y === moved.position.y) {
      nextState = damagePlayer(project, nextState, moved.damage);
      continue;
    }
    if (moved.owner === 'PLAYER') {
      const target = scene.objects.find((object) => {
        const position = nextState.objectPositions[object.id] ?? object.position;
        return isVisible(nextState, object) && position.x === moved.position.x && position.y === moved.position.y
          && object.components.some((component) => component.type === 'HEALTH');
      });
      if (target !== undefined) {
        const health = (nextState.objectHealth[target.id] ?? 1) - moved.damage;
        nextState = {
          ...nextState,
          score: health <= 0 ? nextState.score + 100 : nextState.score,
          defeatedEnemies: health <= 0 ? nextState.defeatedEnemies + 1 : nextState.defeatedEnemies,
          objectHealth: { ...nextState.objectHealth, [target.id]: health },
          session: health <= 0 ? { ...nextState.session, objectVisibility: { ...nextState.session.objectVisibility, [target.id]: false } } : nextState.session,
        };
        continue;
      }
      const dynamicIndex = spawnedEnemies.findIndex((enemy) => enemy.position.x === moved.position.x && enemy.position.y === moved.position.y);
      if (dynamicIndex >= 0) {
        const enemy = spawnedEnemies[dynamicIndex];
        if (enemy !== undefined) {
          const health = enemy.health - moved.damage;
          spawnedEnemies = health <= 0
            ? spawnedEnemies.filter((_, index) => index !== dynamicIndex)
            : spawnedEnemies.map((candidate, index) => index === dynamicIndex ? { ...candidate, health } : candidate);
          if (health <= 0) nextState = {
            ...nextState,
            score: nextState.score + 100,
            defeatedEnemies: nextState.defeatedEnemies + 1,
          };
        }
        continue;
      }
    }
    survivingProjectiles.push(moved);
  }
  const collidingEnemy = spawnedEnemies.find((enemy) => nextState.playerPosition?.x === enemy.position.x && nextState.playerPosition.y === enemy.position.y);
  if (collidingEnemy !== undefined) {
    nextState = damagePlayer(project, nextState, 1);
    spawnedEnemies = spawnedEnemies.filter((enemy) => enemy.id !== collidingEnemy.id);
  }
  nextState = { ...nextState, spawnedEnemies, projectiles: survivingProjectiles, nextEntityId };
  if (nextState.playerPosition === null) return applyCompletionRules(project, nextState);
  for (const occupant of visibleOccupantsAt(nextState, scene.objects, nextState.playerPosition)) {
    nextState = applyContactComponents(project, nextState, occupant);
  }
  if (nextState.playerPosition === null) return applyCompletionRules(project, nextState);
  if (scene.type !== 'PLATFORMER') return applyCompletionRules(project, nextState);
  // S15P21A604-408 — jumpHoldTicks > 0이면 이번 틱의 상승(또는 유지 종료)은 이미
  // movePlayerFromHeldKeys(platformerJumpFrom/platformerContinueJump)가 처리했다. 여기서
  // 또 자동 중력 적분을 돌리면 같은 틱에 두 번 움직이게 되므로 건너뛴다 — 예전(고정
  // 임펄스) 물리가 정확히 그렇게 동작했던 것이라, 지금은 홀드 로직이 그 자리를 대신한다.
  if (nextState.jumpHoldTicks > 0) return applyCompletionRules(project, nextState);
  const grounded = isPlatformerGrounded(nextState, scene);
  if (grounded && nextState.verticalVelocity >= 0) return applyCompletionRules(project, nextState.verticalVelocity === 0 ? nextState : { ...nextState, verticalVelocity: 0 });
  const direction = nextState.verticalVelocity < 0 ? -1 : 1;
  const next = { x: nextState.playerPosition.x, y: nextState.playerPosition.y + direction };
  if (next.y < 0) return applyCompletionRules(project, { ...nextState, verticalVelocity: 0 });
  if (next.y >= scene.height) return applyCompletionRules(project, damagePlayer(project, { ...nextState, verticalVelocity: 0 }, 1));
  if (visibleOccupantsAt(nextState, scene.objects, next).some(isSolid)) return applyCompletionRules(project, { ...nextState, verticalVelocity: 0 });
  return applyCompletionRules(project, enterPosition(project, { ...nextState, verticalVelocity: Math.min(3, nextState.verticalVelocity + 1) }, next));
};

// TOP_DOWN 이동의 실제 한 걸음 — 단일 방향(moveReferencePlayer)과 동시입력 대각선
// (movePlayerFromHeldKeys) 양쪽이 공유한다. delta는 -1/0/1의 x/y 조합(대각선 포함).
const moveTopDownByDelta = (
  project: GameProject,
  state: ReferenceRuntimeState,
  scene: Extract<GameProject['scenes'][number], { type: 'TOP_DOWN' }>,
  delta: Position2d,
  facing: MoveDirection,
): ReferenceRuntimeState => {
  if (state.playerPosition === null) return state;
  const next = { x: state.playerPosition.x + delta.x, y: state.playerPosition.y + delta.y };
  const facingState = { ...state, facing, lastInteractionTargetId: null };
  if (next.x < 0 || next.y < 0 || next.x >= scene.width || next.y >= scene.height) return facingState;
  const occupants = visibleOccupantsAt(state, scene.objects, next);
  if (occupants.some(isSolid)) return facingState;
  return enterPosition(project, facingState, next);
};

// 플랫포머 수평 한 걸음 — 벽/충돌 판정 포함, 막혔으면 위치는 그대로 두고 facing만 갱신한다.
// moveReferencePlayer(LEFT/RIGHT)와 movePlayerFromHeldKeys의 PLATFORMER 대각선 이동이 공유한다.
const platformerStepHorizontal = (
  project: GameProject,
  state: ReferenceRuntimeState,
  scene: Extract<GameProject['scenes'][number], { type: 'PLATFORMER' }>,
  dx: -1 | 1,
): ReferenceRuntimeState => {
  if (state.playerPosition === null) return state;
  const facingState = { ...state, facing: dx < 0 ? 'LEFT' as const : 'RIGHT' as const, lastInteractionTargetId: null };
  const horizontal = { x: state.playerPosition.x + dx, y: state.playerPosition.y };
  if (horizontal.x < 0 || horizontal.x >= scene.width || visibleOccupantsAt(state, scene.objects, horizontal).some(isSolid)) return facingState;
  return enterPosition(project, facingState, horizontal);
};

// S15P21A604-408 — 마리오 스타일 가변 점프의 홀드 유지 상한(틱). 이만큼 UP을 누르고
// 있으면 예전 고정 임펄스(verticalVelocity: -3) 시절과 같은 높이(4칸)에 도달하고, 더 짧게
// 누르면 유지한 틱 수만큼만(선형 비례) 낮게 점프한다.
const JUMP_HOLD_MAX_TICKS = 4;

// 점프 물리 자체 — 접지 판정은 호출부 책임이다(대각선 점프는 이 틱이 "시작한" 접지 상태를
// 기준으로 판정하고, 실제로 뛰어오르는 위치는 이미 반영된 수평 이동 이후의 x를 쓴다 — 발판
// 가장자리에서 대각선으로 뛰어나가는 입력도 점프로 인정하기 위함).
// S15P21A604-408 — 예전엔 여기서 verticalVelocity: -3을 한 번 주면 tickReferenceWorld의
// 자동 감속 적분이 알아서 4칸까지 올려보냈다(홀드 시간과 무관). 이제는 "1칸 상승 + 홀드
// 시작"만 하고, 계속 상승할지는 매 틱 movePlayerFromHeldKeys가 UP이 눌려 있는지 보고
// platformerContinueJump로 이어간다.
const platformerJumpFrom = (
  project: GameProject,
  state: ReferenceRuntimeState,
  scene: Extract<GameProject['scenes'][number], { type: 'PLATFORMER' }>,
): ReferenceRuntimeState => {
  if (state.playerPosition === null) return state;
  const jumpTarget = { x: state.playerPosition.x, y: Math.max(0, state.playerPosition.y - 1) };
  if (visibleOccupantsAt(state, scene.objects, jumpTarget).some(isSolid)) return state;
  return enterPosition(project, { ...state, verticalVelocity: -1, jumpHoldTicks: 1 }, jumpTarget);
};

// 점프 홀드 유지 중(jumpHoldTicks > 0, < JUMP_HOLD_MAX_TICKS)이고 UP이 여전히 눌려 있을 때
// 매 틱 호출 — 1칸 더 상승시키고 유지 틱 수를 늘린다. platformerJumpFrom과 같은 이유로
// 위가 막혀 있으면(천장) 더 못 올라가고 그 자리에서 홀드가 끝난다(하강 전환).
const platformerContinueJump = (
  project: GameProject,
  state: ReferenceRuntimeState,
  scene: Extract<GameProject['scenes'][number], { type: 'PLATFORMER' }>,
): ReferenceRuntimeState => {
  if (state.playerPosition === null) return state;
  const nextTarget = { x: state.playerPosition.x, y: Math.max(0, state.playerPosition.y - 1) };
  if (visibleOccupantsAt(state, scene.objects, nextTarget).some(isSolid)) {
    return { ...state, verticalVelocity: 0, jumpHoldTicks: 0 };
  }
  return enterPosition(project, { ...state, verticalVelocity: -1, jumpHoldTicks: state.jumpHoldTicks + 1 }, nextTarget);
};

export const moveReferencePlayer = (
  project: GameProject,
  state: ReferenceRuntimeState,
  direction: MoveDirection,
): ReferenceRuntimeState => {
  if (state.session.status !== 'PLAYING' || state.session.activeDialogueSceneId !== null) return state;
  const scene = findScene(project, state.session.currentSceneId);
  if (scene === undefined || scene.type === 'DIALOGUE' || state.playerPosition === null) return state;
  if (scene.type === 'PLATFORMER') {
    // S15P21A604-408 — 이 경로(화면 ↑ 버튼 클릭 등 단발성 호출)는 movePlayerFromHeldKeys의
    // 매 틱 held-key 루프에 안 잡히므로 홀드를 이어갈 수 없다 — platformerJumpFrom이 홀드를
    // 시작해도 다음 틱에 movePlayerFromHeldKeys가 "UP 없음"으로 보고 바로 끝내버려서,
    // 결과적으로 최소 높이(1칸) 점프가 된다. 의도된 동작이다.
    if (direction === 'UP') return isPlatformerGrounded(state, scene) ? platformerJumpFrom(project, state, scene) : state;
    if (direction === 'DOWN') return { ...state, verticalVelocity: Math.max(1, state.verticalVelocity) };
    return platformerStepHorizontal(project, state, scene, direction === 'LEFT' ? -1 : 1);
  }
  return moveTopDownByDelta(project, state, scene, directionDelta[direction], direction);
};

// S15P21A604-363 — 눌려 있는 방향키 집합으로 한 틱(REFERENCE_TICK_MS)당 정확히 한 걸음을 옮긴다.
// keydown마다 즉시 이동하던 이전 방식은 OS/브라우저 키 auto-repeat 타이밍(초반 지연 → 빠른 반복)을
// 그대로 따라가 "멈췄다가 급발진"했고, tickCount와 무관하게 이동이 몰아치면서
// damagePlayer의 invulnerableUntilTick(틱 기준 무적)이 실제 경과 시간과 어긋나 함정을
// "스킵"한 것처럼 보이게 했다(실제로는 죽고 즉시 리스폰된 뒤 무적 상태로 통과한 것).
// 이동을 tickReferenceWorld와 같은 120ms 시계에 묶으면 두 증상이 함께 해결된다.
// TOP_DOWN은 두 축을 합성해 진짜 대각선 이동을 지원한다. PLATFORMER는 "점프(UP) > 좌우"
// 배타적 우선순위로 처리하던 이전 버전이 UP이 눌려 있으면 좌우 입력을 통째로 버려서 대각선
// 점프가 구조적으로 불가능했다 — 실제 횡스크롤 게임처럼 수평 이동(좌우)과 수직 상태(점프/
// 낙하)를 독립된 축으로 매 틱 함께 적용하도록 바꿨다(S15P21A604-363 QA에서 발견).
export const movePlayerFromHeldKeys = (
  project: GameProject,
  state: ReferenceRuntimeState,
  directions: ReadonlySet<MoveDirection>,
): ReferenceRuntimeState => {
  if (state.session.status !== 'PLAYING' || state.session.activeDialogueSceneId !== null) return state;
  const scene = findScene(project, state.session.currentSceneId);
  if (scene === undefined || scene.type === 'DIALOGUE' || state.playerPosition === null) return state;
  if (directions.size === 0) {
    // S15P21A604-408 — 아무 키도 안 잡혀 있어도(다 뗐거나, moveReferencePlayer의 화면 ↑
    // 버튼 클릭처럼 애초에 이 held-key tick 루프 밖에서 시작된 점프라 이번 틱에 아무것도
    // 안 잡히는 경우) 홀드 중이던 점프는 끝내야 한다 — 안 그러면(예전 코드처럼 여기서 바로
    // return state) tickReferenceWorld가 jumpHoldTicks>0인 동안 계속 중력 처리를 건너뛰어
    // 공중에 영원히 멈춰버린다.
    if (scene.type === 'PLATFORMER' && state.jumpHoldTicks > 0) {
      return { ...state, jumpHoldTicks: 0, verticalVelocity: 0 };
    }
    return state;
  }

  if (scene.type === 'PLATFORMER') {
    const left = directions.has('LEFT');
    const right = directions.has('RIGHT');
    const up = directions.has('UP');
    const down = directions.has('DOWN');
    // 접지 판정은 이번 틱이 "시작한" 위치 기준 — 발판 가장자리에서 대각선으로 뛰어나가는
    // 입력도 점프로 인정한다(수평 이동 이후 위치로 판정하면 그 프레임에 걸어 나간 순간
    // 공중 판정이 되어 막혀버린다).
    const canJump = up && !down && isPlatformerGrounded(state, scene);
    // S15P21A604-408 — 이전 틱에 시작한 점프를 이번 틱에도 UP이 눌려 있는 동안
    // (JUMP_HOLD_MAX_TICKS까지) 이어서 유지한다. canJump과는 배타적이다(그라운드에서 새로
    // 시작하는 tick과 이미 공중에서 유지 중인 tick은 겹치지 않는다).
    const holdingJump = !canJump && up && state.jumpHoldTicks > 0 && state.jumpHoldTicks < JUMP_HOLD_MAX_TICKS;
    let next = state;
    if (left !== right) next = platformerStepHorizontal(project, next, scene, left ? -1 : 1);
    if (canJump) {
      next = platformerJumpFrom(project, next, scene);
    } else if (holdingJump) {
      next = platformerContinueJump(project, next, scene);
    } else if (next.jumpHoldTicks > 0) {
      // holdingJump가 false인데도 jumpHoldTicks가 남아있다는 건 UP을 놓았거나(다른 키만
      // held) 상한(JUMP_HOLD_MAX_TICKS)에 닿았다는 뜻 — 홀드 종료, 다음 tickReferenceWorld
      // 부터 중력이 이어받는다. (위에서 이미 좌우 이동은 반영된 뒤라 여기선 수직만 정리한다.)
      next = { ...next, jumpHoldTicks: 0, verticalVelocity: 0 };
    } else if (down && !up) {
      next = { ...next, verticalVelocity: Math.max(1, next.verticalVelocity) };
    }
    return next;
  }

  let dx = 0;
  let dy = 0;
  if (directions.has('LEFT')) dx -= 1;
  if (directions.has('RIGHT')) dx += 1;
  if (directions.has('UP')) dy -= 1;
  if (directions.has('DOWN')) dy += 1;
  if (dx === 0 && dy === 0) return state; // 반대 방향 동시 입력은 상쇄되어 제자리
  const facing: MoveDirection = dx !== 0 ? (dx < 0 ? 'LEFT' : 'RIGHT') : (dy < 0 ? 'UP' : 'DOWN');
  return moveTopDownByDelta(project, state, scene, { x: dx, y: dy }, facing);
};

const interactionCandidates = (
  project: GameProject,
  state: ReferenceRuntimeState,
): readonly GameObject[] => {
  const scene = findScene(project, state.session.currentSceneId);
  if (scene === undefined || scene.type === 'DIALOGUE' || state.playerPosition === null) return [];
  const forward = directionDelta[state.facing];
  const forwardPosition = {
    x: state.playerPosition.x + forward.x,
    y: state.playerPosition.y + forward.y,
  };
  return scene.objects.filter((object) => {
    if (!isVisible(state, object)) return false;
    const objectPosition = state.objectPositions[object.id] ?? object.position;
    const onCurrent = objectPosition.x === state.playerPosition?.x && objectPosition.y === state.playerPosition.y;
    const inFront = objectPosition.x === forwardPosition.x && objectPosition.y === forwardPosition.y;
    const interactive = object.components.some((component) => component.type === 'INTERACTABLE')
      || scene.events.some((event) => event.trigger.type === 'ON_INTERACT' && event.trigger.targetId === object.id);
    return interactive && (onCurrent || inFront);
  });
};

export const interactReferencePlayer = (
  project: GameProject,
  state: ReferenceRuntimeState,
): ReferenceRuntimeState => {
  if (state.session.status !== 'PLAYING' || state.session.activeDialogueSceneId !== null) return state;
  const target = interactionCandidates(project, state)[0];
  if (target === undefined) return { ...state, lastInteractionTargetId: null };
  const session = dispatchTrigger(project, state.session, { type: 'ON_INTERACT', targetId: target.id });
  return {
    ...syncAfterSessionChange(project, state, session),
    lastInteractionTargetId: target.id,
  };
};

export const chooseReferenceDialogue = (
  project: GameProject,
  state: ReferenceRuntimeState,
  choiceId: string,
): ReferenceRuntimeState => syncAfterSessionChange(
  project,
  state,
  chooseDialogueChoice(project, state.session, choiceId),
);
