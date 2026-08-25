import { describe, expect, it } from 'vitest';
import { parseGameProject } from '../../contracts/gameProject.ts';
import { createProjectFromTemplate } from '../../studio/model/projectTemplates.ts';
import {
  moveReferencePlayer,
  shootReferenceProjectile,
  startReferenceRuntime,
  tickReferenceWorld,
} from '../../runtime/reference/referenceRuntime.ts';

describe('reference runtime gameplay systems', () => {
  it('runs platform gravity and jump without a separate game document format', () => {
    const project = createProjectFromTemplate(123, 'PLATFORMER');
    const started = startReferenceRuntime(project);
    const jumped = moveReferencePlayer(project, started, 'UP');
    expect(jumped.playerPosition?.y).toBeLessThan(started.playerPosition?.y ?? 0);
    expect(jumped.verticalVelocity).toBeLessThan(0);
    let afterGravity = jumped;
    for (let frame = 0; frame < 6; frame += 1) {
      afterGravity = tickReferenceWorld(project, afterGravity);
    }
    expect(afterGravity.playerPosition?.y).toBeGreaterThanOrEqual(jumped.playerPosition?.y ?? 0);
  });

  it('fires a player projectile and damages a configured enemy', () => {
    const project = createProjectFromTemplate(123, 'SHOOTER');
    const started = startReferenceRuntime(project);
    const fired = shootReferenceProjectile(project, started);
    expect(fired.projectiles).toHaveLength(1);
    const advanced = tickReferenceWorld(project, tickReferenceWorld(project, fired));
    expect(advanced.objectHealth.slime1).toBe(2);
    expect(advanced.projectiles).toHaveLength(0);
  });

  it('spawns a bounded enemy wave from a survival template', () => {
    const project = createProjectFromTemplate(123, 'SURVIVAL');
    const started = startReferenceRuntime(project);
    let advanced = started;
    for (let frame = 0; frame < 25; frame += 1) {
      advanced = tickReferenceWorld(project, advanced);
    }
    expect(advanced.spawnedEnemies).toHaveLength(1);
  });

  it('gives the player a short recovery window after enemy contact', () => {
    const original = createProjectFromTemplate(123, 'SHOOTER');
    const scene = original.scenes[0];
    if (scene?.type !== 'PLATFORMER') throw new Error('shooter scene must be platformer');
    const project = parseGameProject({
      ...original,
      scenes: [{
        ...scene,
        objects: scene.objects.map((object) => object.id === 'slime1'
          ? { ...object, components: object.components.filter((component) => component.type !== 'AUTO_MOVE') }
          : object),
      }],
    });
    const started = { ...startReferenceRuntime(project), playerPosition: { x: 5, y: 10 } };
    const damaged = tickReferenceWorld(project, started);
    const protectedFrame = tickReferenceWorld(project, damaged);
    expect(damaged.playerHealth).toBe(2);
    expect(protectedFrame.playerHealth).toBe(2);
  });

  it('applies a pickup event and score component together before hiding the object', () => {
    const original = createProjectFromTemplate(123, 'COLLECTION');
    const scene = original.scenes[0];
    if (scene?.type !== 'TOP_DOWN') throw new Error('collection scene must be top down');
    const project = parseGameProject({
      ...original,
      scenes: [{
        ...scene,
        objects: scene.objects.map((object) => object.id === 'treasure1'
          ? {
              ...object,
              components: object.components.some((component) => component.type === 'SCORE_VALUE')
                ? object.components.map((component) => component.type === 'SCORE_VALUE' ? { ...component, value: 25 } : component)
                : [...object.components, { type: 'SCORE_VALUE' as const, value: 25 }],
            }
          : object),
      }],
    });
    const started = { ...startReferenceRuntime(project), playerPosition: { x: 2, y: 6 } };
    const collected = moveReferencePlayer(project, started, 'RIGHT');
    expect(collected.score).toBe(25);
    expect(collected.session.inventory.has('moonGem')).toBe(true);
    expect(collected.session.objectVisibility.treasure1).toBe(false);
  });

  it('completes score, enemy defeat, and survival objectives deterministically', () => {
    const collectionOriginal = createProjectFromTemplate(124, 'COLLECTION');
    const collection = parseGameProject({
      ...collectionOriginal,
      rules: { completion: { mode: 'ALL', objectives: [{ type: 'SCORE_AT_LEAST', target: 100 }] }, playerDefeat: 'RESPAWN' },
    });
    const scored = moveReferencePlayer(collection, { ...startReferenceRuntime(collection), playerPosition: { x: 2, y: 6 } }, 'RIGHT');
    expect(scored.session.status).toBe('COMPLETED');

    const shooterOriginal = createProjectFromTemplate(125, 'SHOOTER');
    const shooter = parseGameProject({
      ...shooterOriginal,
      rules: { completion: { mode: 'ALL', objectives: [{ type: 'DEFEAT_ENEMIES', target: 1 }] }, playerDefeat: 'RESPAWN' },
    });
    const shooterStarted = startReferenceRuntime(shooter);
    const fired = shootReferenceProjectile(shooter, { ...shooterStarted, objectHealth: { ...shooterStarted.objectHealth, slime1: 1 } });
    const defeated = tickReferenceWorld(shooter, tickReferenceWorld(shooter, fired));
    expect(defeated.defeatedEnemies).toBe(1);
    expect(defeated.session.status).toBe('COMPLETED');

    const survivalOriginal = createProjectFromTemplate(126, 'SURVIVAL');
    const survival = parseGameProject({
      ...survivalOriginal,
      rules: { completion: { mode: 'ALL', objectives: [{ type: 'SURVIVE_SECONDS', target: 1 }] }, playerDefeat: 'END_GAME' },
    });
    let survived = startReferenceRuntime(survival);
    for (let frame = 0; frame < 9; frame += 1) survived = tickReferenceWorld(survival, survived);
    expect(survived.elapsedMs).toBe(1080);
    expect(survived.session.status).toBe('COMPLETED');
  });

  it('ends the challenge when an END_GAME project reaches zero health', () => {
    const project = createProjectFromTemplate(127, 'SURVIVAL');
    const started = { ...startReferenceRuntime(project), playerPosition: { x: 7, y: 10 }, playerHealth: 1 };
    const defeated = tickReferenceWorld(project, started);
    expect(defeated.playerHealth).toBe(0);
    expect(defeated.session.status).toBe('FAILED');
    expect(defeated.session.failure?.code).toBe('PLAYER_DEFEATED');
  });
});
