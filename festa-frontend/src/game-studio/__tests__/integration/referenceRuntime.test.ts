import { describe, expect, it } from 'vitest';
import { getActiveDialogue } from '../../runtime/dialogue/dialogueRunner.ts';
import {
  chooseReferenceDialogue,
  interactReferencePlayer,
  moveReferencePlayer,
  startReferenceRuntime,
} from '../../runtime/reference/referenceRuntime.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { parseGameProject } from '../../contracts/gameProject.ts';
import { addTopDownScene } from '../../studio/model/authoringCommands.ts';

describe('reference web runtime', () => {
  it('plays a creator-authored item, condition, scene transition, and completion flow', () => {
    const project = createStarterProject(61);
    let runtime = startReferenceRuntime(project);

    runtime = moveReferencePlayer(project, runtime, 'RIGHT');
    runtime = moveReferencePlayer(project, runtime, 'RIGHT');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    expect(runtime.playerPosition).toEqual({ x: 10, y: 4 });
    expect(runtime.session.inventory.has('libraryKey')).toBe(true);
    expect(runtime.session.objectVisibility.libraryKeyObject).toBe(false);

    runtime = moveReferencePlayer(project, runtime, 'LEFT');
    runtime = moveReferencePlayer(project, runtime, 'LEFT');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    runtime = interactReferencePlayer(project, runtime);
    expect(runtime.session.currentSceneId).toBe('ending');
    expect(getActiveDialogue(project, runtime.session)?.node.id).toBe('escaped');

    runtime = chooseReferenceDialogue(project, runtime, 'finish');
    expect(runtime.session.status).toBe('COMPLETED');
  });

  it('opens and closes an overlay dialogue while preserving map position', () => {
    const project = createStarterProject(62);
    let runtime = startReferenceRuntime(project);
    runtime = moveReferencePlayer(project, runtime, 'LEFT');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    runtime = interactReferencePlayer(project, runtime);

    expect(runtime.session.activeDialogueSceneId).toBe('librarianDialogue');
    expect(runtime.playerPosition).toEqual({ x: 7, y: 5 });
    runtime = chooseReferenceDialogue(project, runtime, 'thanks');
    expect(runtime.session.activeDialogueSceneId).toBeNull();
    expect(runtime.session.currentSceneId).toBe('library');
    expect(runtime.playerPosition).toEqual({ x: 7, y: 5 });
  });

  it('preserves health, inventory, variables, and object state across a world Scene transition', () => {
    const extended = addTopDownScene(createStarterProject(63));
    const destination = extended.scenes.at(-1);
    if (destination?.type !== 'TOP_DOWN') throw new Error('expected destination map');
    const project = parseGameProject({
      ...extended,
      scenes: extended.scenes.map((scene) => scene.id !== 'library' || scene.type !== 'TOP_DOWN' ? scene : {
        ...scene,
        objects: [...scene.objects, {
          id: 'entryHazard',
          preset: 'HAZARD' as const,
          position: { x: 9, y: 8 },
          visible: true,
          components: [{ type: 'DAMAGE' as const, amount: 1 }],
        }],
        events: scene.events.map((event) => event.id !== 'openLockedDoor' ? event : {
          ...event,
          actions: [
            { type: 'SET_VARIABLE' as const, variableId: 'doorOpened', value: true },
            { type: 'GO_TO_SCENE' as const, sceneId: destination.id },
          ],
        }),
      }),
    });

    let runtime = startReferenceRuntime(project);
    runtime = moveReferencePlayer(project, runtime, 'RIGHT');
    expect(runtime.playerHealth).toBe(2);
    runtime = moveReferencePlayer(project, runtime, 'RIGHT');
    for (let index = 0; index < 4; index += 1) runtime = moveReferencePlayer(project, runtime, 'UP');
    expect(runtime.session.inventory.has('libraryKey')).toBe(true);
    expect(runtime.session.objectVisibility.libraryKeyObject).toBe(false);
    runtime = moveReferencePlayer(project, runtime, 'LEFT');
    runtime = moveReferencePlayer(project, runtime, 'LEFT');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    runtime = moveReferencePlayer(project, runtime, 'UP');
    runtime = interactReferencePlayer(project, runtime);

    expect(runtime.session.currentSceneId).toBe(destination.id);
    expect(runtime.session.variables.doorOpened).toBe(true);
    expect(runtime.session.inventory.has('libraryKey')).toBe(true);
    expect(runtime.session.objectVisibility.libraryKeyObject).toBe(false);
    expect(runtime.playerHealth).toBe(2);
    expect(runtime.playerPosition).toEqual(destination.objects.find((object) => object.preset === 'PLAYER_SPAWN')?.position);
  });
});
