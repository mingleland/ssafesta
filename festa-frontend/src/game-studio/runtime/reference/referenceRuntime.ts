import { findScene, type GameObject, type GameProject, type Position2d } from '../../contracts/gameProject.ts';
import { dispatchTrigger, startRuntimeSession } from '../../core/runEvent.ts';
import type { RuntimeSessionState } from '../../core/runtimeState.ts';
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
  readonly playerHealth: number;
  readonly maxPlayerHealth: number;
  readonly invulnerableUntilTick: number;
  readonly score: number;
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
    playerHealth: 3,
    maxPlayerHealth: 3,
    invulnerableUntilTick: -1,
    score: 0,
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
  return {
    ...state,
    playerHealth: state.maxPlayerHealth,
    playerPosition: state.checkpointPosition ?? topDownSpawn(project, state.session.currentSceneId),
    verticalVelocity: 0,
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
    const session = dispatchTrigger(project, moved.session, { type: 'ON_ENTER', targetId: object.id });
    const contacted = applyContactComponents(project, moved, object);
    moved = syncAfterSessionChange(project, contacted, session);
    if (session.status !== 'PLAYING' || session.currentSceneId !== scene.id || session.activeDialogueSceneId !== null) break;
  }
  return moved;
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
  let nextState: ReferenceRuntimeState = { ...state, tickCount: state.tickCount + 1 };
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
          if (health <= 0) nextState = { ...nextState, score: nextState.score + 100 };
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
  if (nextState.playerPosition === null) return nextState;
  for (const occupant of visibleOccupantsAt(nextState, scene.objects, nextState.playerPosition)) {
    nextState = applyContactComponents(project, nextState, occupant);
  }
  if (nextState.playerPosition === null) return nextState;
  if (scene.type !== 'PLATFORMER') return nextState;
  const grounded = isPlatformerGrounded(nextState, scene);
  if (grounded && nextState.verticalVelocity >= 0) return nextState.verticalVelocity === 0 ? nextState : { ...nextState, verticalVelocity: 0 };
  const direction = nextState.verticalVelocity < 0 ? -1 : 1;
  const next = { x: nextState.playerPosition.x, y: nextState.playerPosition.y + direction };
  if (next.y < 0) return { ...nextState, verticalVelocity: 0 };
  if (next.y >= scene.height) return damagePlayer(project, { ...nextState, verticalVelocity: 0 }, 1);
  if (visibleOccupantsAt(nextState, scene.objects, next).some(isSolid)) return { ...nextState, verticalVelocity: 0 };
  return enterPosition(project, { ...nextState, verticalVelocity: Math.min(3, nextState.verticalVelocity + 1) }, next);
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
    const facingState = direction === 'LEFT' || direction === 'RIGHT'
      ? { ...state, facing: direction, lastInteractionTargetId: null }
      : state;
    if (direction === 'UP') {
      if (!isPlatformerGrounded(state, scene)) return state;
      const jumpTarget = { x: state.playerPosition.x, y: Math.max(0, state.playerPosition.y - 1) };
      if (visibleOccupantsAt(state, scene.objects, jumpTarget).some(isSolid)) return state;
      return enterPosition(project, { ...facingState, verticalVelocity: -3 }, jumpTarget);
    }
    if (direction === 'DOWN') return { ...facingState, verticalVelocity: Math.max(1, state.verticalVelocity) };
    const horizontal = { x: state.playerPosition.x + (direction === 'LEFT' ? -1 : 1), y: state.playerPosition.y };
    if (horizontal.x < 0 || horizontal.x >= scene.width || visibleOccupantsAt(state, scene.objects, horizontal).some(isSolid)) return facingState;
    return enterPosition(project, facingState, horizontal);
  }
  const delta = directionDelta[direction];
  const next = { x: state.playerPosition.x + delta.x, y: state.playerPosition.y + delta.y };
  const facingState = { ...state, facing: direction, lastInteractionTargetId: null };
  if (next.x < 0 || next.y < 0 || next.x >= scene.width || next.y >= scene.height) return facingState;
  const occupants = visibleOccupantsAt(state, scene.objects, next);
  if (occupants.some(isSolid)) return facingState;

  return enterPosition(project, facingState, next);
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
