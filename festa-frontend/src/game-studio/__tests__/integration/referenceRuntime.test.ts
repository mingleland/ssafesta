import { describe, expect, it } from 'vitest';
import { getActiveDialogue } from '../../runtime/dialogue/dialogueRunner.ts';
import {
  chooseReferenceDialogue,
  currentInteractionTarget,
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

// S15P21A604-532 — 오브젝트 위 상호작용 안내 문구를 띄우려면 "지금 상호작용 가능한
// 대상이 뭔지"를 UI(ReferenceGamePlayer)에서도 계산할 수 있어야 한다. interactReferencePlayer
// 내부에서만 쓰이던 판정 로직을 currentInteractionTarget으로 export해 그대로 재사용한다 —
// 실제 상호작용(E 버튼)의 판정과 힌트 표시 판정이 어긋나면 "버튼은 되는데 힌트가 안 뜨는"
// 식의 불일치가 생기므로, 반드시 같은 함수를 써야 한다.
describe('reference web runtime — currentInteractionTarget(S15P21A604-532)', () => {
  it('상호작용 범위(정면) 안에 있으면 그 오브젝트를 대상으로 잡는다', () => {
    const project = createStarterProject(532);
    // library 씬의 librarian(INTERACTABLE, prompt: '대화하기')은 (7,4)에 있다 — 그 한 칸
    // 아래(7,5)에서 위를 보고 있으면 정면이 librarian과 겹친다.
    const runtime = { ...startReferenceRuntime(project), playerPosition: { x: 7, y: 5 }, facing: 'UP' as const };
    expect(currentInteractionTarget(project, runtime)?.id).toBe('librarian');
  });

  it('범위 밖이면 대상이 없다(null)', () => {
    const project = createStarterProject(532);
    const runtime = { ...startReferenceRuntime(project), playerPosition: { x: 0, y: 0 }, facing: 'UP' as const };
    expect(currentInteractionTarget(project, runtime)).toBeNull();
  });

  it('INTERACTABLE 컴포넌트 없이 ON_INTERACT 이벤트로만 상호작용 가능한 오브젝트도 대상으로 잡힌다', () => {
    // interactReferencePlayer(실제 E 버튼)의 판정과 어긋나지 않아야 하므로, "대상으로
    // 잡히는지" 자체는 INTERACTABLE 컴포넌트 유무와 무관해야 한다 — 안내 문구를 보여줄지
    // 말지는 이 함수가 아니라 UI 쪽에서 prompt 유무로 따로 결정한다(Phase 2).
    const original = createStarterProject(532);
    const project = parseGameProject({
      ...original,
      scenes: original.scenes.map((scene) => scene.id !== 'library' || scene.type !== 'TOP_DOWN' ? scene : {
        ...scene,
        objects: [...scene.objects, {
          id: 'leverNoPrompt',
          preset: 'INTERACTABLE' as const,
          position: { x: 7, y: 5 },
          visible: true,
          components: [],
        }],
        events: [...scene.events, {
          id: 'pullLever',
          trigger: { type: 'ON_INTERACT' as const, targetId: 'leverNoPrompt' },
          conditions: [],
          actions: [{ type: 'SET_VARIABLE' as const, variableId: 'doorOpened', value: true }],
        }],
      }),
    });
    const runtime = { ...startReferenceRuntime(project), playerPosition: { x: 7, y: 6 }, facing: 'UP' as const };
    expect(currentInteractionTarget(project, runtime)?.id).toBe('leverNoPrompt');
  });
});
