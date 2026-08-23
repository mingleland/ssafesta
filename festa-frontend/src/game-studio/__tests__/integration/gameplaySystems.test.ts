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
          ? { ...object, components: [...object.components, { type: 'SCORE_VALUE' as const, value: 25 }] }
          : object),
      }],
    });
    const started = { ...startReferenceRuntime(project), playerPosition: { x: 2, y: 6 } };
    const collected = moveReferencePlayer(project, started, 'RIGHT');
    expect(collected.score).toBe(25);
    expect(collected.session.inventory.has('moonGem')).toBe(true);
    expect(collected.session.objectVisibility.treasure1).toBe(false);
  });
});
