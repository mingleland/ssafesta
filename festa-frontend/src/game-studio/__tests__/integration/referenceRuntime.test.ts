import { describe, expect, it } from 'vitest';
import { getActiveDialogue } from '../../runtime/dialogue/dialogueRunner.ts';
import {
  chooseReferenceDialogue,
  interactReferencePlayer,
  moveReferencePlayer,
  startReferenceRuntime,
} from '../../runtime/reference/referenceRuntime.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

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
});
