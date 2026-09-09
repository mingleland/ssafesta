import { describe, expect, it } from 'vitest';
import { GameProjectContractError, parseGameProject } from '../../contracts/gameProject.ts';
import {
  addBooleanVariable,
  addComponent,
  addItemDefinition,
  addDialogueChoice,
  addDialogueNode,
  addDialogueScene,
  addObject,
  addObjectEvent,
  addPlatformerScene,
  addTileLayer,
  addTopDownScene,
  appendEventAction,
  appendEventCondition,
  copyObjectsToScene,
  dialogueNodeRemovalReason,
  duplicateScene,
  duplicateObjects,
  fillTileLayer,
  floodFillTiles,
  isTerminalActionType,
  moveObject,
  moveObjects,
  moveScene,
  paintTile,
  paintTiles,
  removeDialogueNode,
  removeItemDefinition,
  removeObjects,
  removeVariableDefinition,
  renameObject,
  reorderDialogueNode,
  reorderEventAction,
  resizeWorldScene,
  sceneRemovalReason,
  setObjectNameVisible,
  setStartNode,
  setStartScene,
  startNodeChangeReason,
  startSceneChangeReason,
  updateDialogueChoice,
} from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

describe('Game Studio authoring commands', () => {
  it('builds generic scenes and keeps every intermediate project contract-valid', () => {
    let project = parseGameProject(createStarterProject(41));
    project = addTopDownScene(project);
    project = addDialogueScene(project, 'OVERLAY');

    const addedMap = project.scenes.at(-2);
    expect(addedMap?.type).toBe('TOP_DOWN');
    if (addedMap?.type !== 'TOP_DOWN') throw new Error('expected TOP_DOWN');
    expect(addedMap.objects.filter((object) => object.preset === 'PLAYER_SPAWN')).toHaveLength(1);
    expect(parseGameProject(project)).toBe(project);
  });

  // S15P21A604-488 — Overlay/Fullscreen은 둘 다 내부적으로 scene.type === 'DIALOGUE'라서,
  // 번호를 presentation까지 따져서 세지 않으면 한쪽 카운터를 다른 쪽이 그대로 이어받는다
  // (예: Overlay를 먼저 만들면 Fullscreen을 처음 만들어도 1이 아니라 2가 됨). starter
  // project가 이미 각 타입/presentation을 하나씩 갖고 있어 절대 번호(1, 2...)를 그대로
  // 가정하면 fixture 변경에 취약하므로, "추가 전 개수 + 1"로 상대 비교한다.
  it('numbers each newly added scene independently per type, and independently per dialogue presentation', () => {
    let project = parseGameProject(createStarterProject(488));

    const topDownCount = () => project.scenes.filter((scene) => scene.type === 'TOP_DOWN').length;
    const platformerCount = () => project.scenes.filter((scene) => scene.type === 'PLATFORMER').length;
    const overlayCount = () => project.scenes.filter((scene) => scene.type === 'DIALOGUE' && scene.presentation === 'OVERLAY').length;
    const fullscreenCount = () => project.scenes.filter((scene) => scene.type === 'DIALOGUE' && scene.presentation === 'FULL_SCREEN').length;

    const topDownBefore = topDownCount();
    project = addTopDownScene(project);
    expect(project.scenes.at(-1)?.name).toBe(`새 맵-TopDown ${topDownBefore + 1}`);

    const platformerBefore = platformerCount();
    project = addPlatformerScene(project);
    expect(project.scenes.at(-1)?.name).toBe(`새 맵-SideScroll ${platformerBefore + 1}`);

    const overlayBefore = overlayCount();
    const fullscreenBefore = fullscreenCount();

    project = addDialogueScene(project, 'OVERLAY');
    expect(project.scenes.at(-1)?.name).toBe(`새 대화-Overlay ${overlayBefore + 1}`);

    // 방금 Overlay를 추가했지만 Fullscreen 카운터는 그 영향을 받지 않아야 한다(독립).
    project = addDialogueScene(project, 'FULL_SCREEN');
    expect(project.scenes.at(-1)?.name).toBe(`새 대화-Fullscreen ${fullscreenBefore + 1}`);

    project = addDialogueScene(project, 'OVERLAY');
    expect(project.scenes.at(-1)?.name).toBe(`새 대화-Overlay ${overlayBefore + 2}`);
  });

  it('places, clamps, componentizes, and scripts an object without a genre-specific model', () => {
    let project = createStarterProject(42);
    const placed = addObject(project, 'library', 'INTERACTABLE', { x: 99, y: -4 });
    project = placed.project;
    const objectId = placed.objectId;
    project = moveObject(project, 'library', objectId, 4, 6);
    project = addComponent(project, 'library', objectId, 'COLLIDER');
    const event = addObjectEvent(project, 'library', objectId);
    project = event.project;
    project = appendEventCondition(project, 'library', event.eventId, 'HAS_ITEM');
    project = appendEventAction(project, 'library', event.eventId, 'SET_VARIABLE');

    const library = project.scenes.find((scene) => scene.id === 'library');
    if (library?.type !== 'TOP_DOWN') throw new Error('expected library');
    const object = library.objects.find((candidate) => candidate.id === objectId);
    expect(object?.position).toEqual({ x: 4, y: 6 });
    expect(object?.components.map((component) => component.type)).toContain('COLLIDER');
    expect(library.events.find((candidate) => candidate.id === event.eventId)?.conditions).toHaveLength(1);
    expect(parseGameProject(project)).toBe(project);
  });

  it('creates and paints a tile layer with fill and erase operations', () => {
    let project = createStarterProject(43);
    const layer = addTileLayer(project, 'library');
    project = fillTileLayer(layer.project, 'library', layer.layerId, 3);
    project = paintTile(project, 'library', layer.layerId, 2, 4, 7);
    project = paintTile(project, 'library', layer.layerId, 0, 0, -1);
    project = paintTiles(project, 'library', layer.layerId, [{ x: 1, y: 1 }, { x: 2, y: 1 }, { x: 2, y: 1 }], 5);

    const library = project.scenes.find((scene) => scene.id === 'library');
    if (library?.type !== 'TOP_DOWN') throw new Error('expected library');
    const data = library.tileLayers[0]?.data;
    expect(data).toHaveLength(library.width * library.height);
    expect(data?.[4 * library.width + 2]).toBe(7);
    expect(data?.[0]).toBe(-1);
    expect(data?.[library.width + 1]).toBe(5);
    expect(data?.[library.width + 2]).toBe(5);
    expect(parseGameProject(project)).toBe(project);
  });

  it('moves a selection as one formation and clamps the whole group at map bounds', () => {
    let project = createStarterProject(44);
    const first = addObject(project, 'library', 'DECORATION', { x: 14, y: 7 });
    project = first.project;
    const second = addObject(project, 'library', 'DECORATION', { x: 15, y: 8 });
    project = moveObjects(second.project, 'library', [first.objectId, second.objectId], 8, 8);

    const library = project.scenes.find((scene) => scene.id === 'library');
    if (library?.type !== 'TOP_DOWN') throw new Error('expected library');
    expect(library.objects.find((object) => object.id === first.objectId)?.position).toEqual({ x: 14, y: 8 });
    expect(library.objects.find((object) => object.id === second.objectId)?.position).toEqual({ x: 15, y: 9 });
  });

  it('duplicates selected objects with their object-triggered behavior and fresh references', () => {
    let project = createStarterProject(45);
    const result = duplicateObjects(project, 'library', ['libraryKeyObject']);
    project = result.project;

    const library = project.scenes.find((scene) => scene.id === 'library');
    if (library?.type !== 'TOP_DOWN') throw new Error('expected library');
    const duplicateId = result.objectIds[0];
    expect(duplicateId).toBeDefined();
    expect(library.objects.find((object) => object.id === duplicateId)?.position).toEqual({ x: 11, y: 5 });
    const copiedEvent = library.events.find((event) => event.trigger.type !== 'ON_SCENE_START' && event.trigger.targetId === duplicateId);
    expect(copiedEvent?.actions).toContainEqual({ type: 'HIDE_OBJECT', objectId: duplicateId });
    expect(parseGameProject(project)).toBe(project);
  });

  it('copies an object formation and its triggered behavior into another world scene', () => {
    let project = addTopDownScene(createStarterProject(48));
    const target = project.scenes.at(-1);
    if (target?.type !== 'TOP_DOWN') throw new Error('expected target TOP_DOWN');
    const result = copyObjectsToScene(project, 'library', target.id, ['librarian', 'libraryKeyObject']);
    project = result.project;

    const copiedScene = project.scenes.find((scene) => scene.id === target.id);
    if (copiedScene?.type !== 'TOP_DOWN') throw new Error('expected copied scene');
    expect(result.objectIds).toHaveLength(2);
    expect(result.objectIds.map((id) => copiedScene.objects.find((object) => object.id === id)?.position)).toEqual([
      { x: 1, y: 1 },
      { x: 4, y: 1 },
    ]);
    const copiedKeyId = result.objectIds[1];
    const copiedPickup = copiedScene.events.find((event) => event.trigger.type !== 'ON_SCENE_START' && event.trigger.targetId === copiedKeyId);
    expect(copiedPickup?.actions).toContainEqual({ type: 'HIDE_OBJECT', objectId: copiedKeyId });
    expect(parseGameProject(project)).toBe(project);
  });

  it('duplicates complete world and dialogue scenes with collision-free internal references', () => {
    let project = createStarterProject(49);
    const worldResult = duplicateScene(project, 'library');
    project = worldResult.project;
    const copiedWorld = project.scenes.find((scene) => scene.id === worldResult.sceneId);
    if (copiedWorld?.type !== 'TOP_DOWN') throw new Error('expected copied world');
    const sourceWorld = project.scenes.find((scene) => scene.id === 'library');
    if (sourceWorld?.type !== 'TOP_DOWN') throw new Error('expected source world');
    const sourceObjectIds = new Set(sourceWorld.objects.map((object) => object.id));
    expect(copiedWorld.objects.every((object) => !sourceObjectIds.has(object.id))).toBe(true);
    const copiedPickup = copiedWorld.events.find((event) => event.actions.some((action) => action.type === 'GIVE_ITEM'));
    const copiedPickupTarget = copiedPickup?.trigger.type === 'ON_ENTER' ? copiedPickup.trigger.targetId : null;
    expect(copiedPickup?.actions).toContainEqual({ type: 'HIDE_OBJECT', objectId: copiedPickupTarget });

    const dialogueResult = duplicateScene(project, 'librarianDialogue');
    project = dialogueResult.project;
    const copiedDialogue = project.scenes.find((scene) => scene.id === dialogueResult.sceneId);
    if (copiedDialogue?.type !== 'DIALOGUE') throw new Error('expected copied dialogue');
    expect(copiedDialogue.startNodeId).not.toBe('welcome');
    expect(copiedDialogue.nodes[0]?.choices[0]?.id).not.toBe('thanks');
    expect(project.startSceneId).toBe('library');
    expect(parseGameProject(project)).toBe(project);
  });

  it('reorders scenes without changing the runtime start scene', () => {
    const project = createStarterProject(50);
    const moved = moveScene(project, 'ending', -1);
    expect(moved.scenes.map((scene) => scene.id)).toEqual(['library', 'ending', 'librarianDialogue']);
    expect(moved.startSceneId).toBe('library');
  });

  it('flood-fills only the connected tile region bounded by another tile', () => {
    let project = createStarterProject(51);
    const layer = addTileLayer(project, 'library');
    project = layer.project;
    const divider = Array.from({ length: 10 }, (_, y) => ({ x: 1, y }));
    project = paintTiles(project, 'library', layer.layerId, divider, 9);
    project = floodFillTiles(project, 'library', layer.layerId, 0, 0, 4);

    const library = project.scenes.find((scene) => scene.id === 'library');
    if (library?.type !== 'TOP_DOWN') throw new Error('expected library');
    const data = library.tileLayers[0]?.data;
    expect(data?.[0]).toBe(4);
    expect(data?.[1]).toBe(9);
    expect(data?.[2]).toBe(-1);
    expect(data?.[9 * library.width]).toBe(4);
    expect(parseGameProject(project)).toBe(project);
  });

  it('deletes self-scripted objects together while preserving protected and externally referenced objects', () => {
    const project = createStarterProject(46);
    const result = removeObjects(project, 'library', ['playerSpawn', 'libraryKeyObject']);
    const library = result.project.scenes.find((scene) => scene.id === 'library');
    if (library?.type !== 'TOP_DOWN') throw new Error('expected library');

    expect(result.removedObjectIds).toEqual(['libraryKeyObject']);
    expect(result.blocked).toContainEqual({ objectId: 'playerSpawn', reason: '플레이어 시작점은 삭제할 수 없습니다.' });
    expect(library.objects.some((object) => object.id === 'libraryKeyObject')).toBe(false);
    expect(library.events.some((event) => event.id === 'takeLibraryKey')).toBe(false);
  });

  it('resizes a world scene while preserving overlapping tile data and clamping objects', () => {
    let project = createStarterProject(47);
    const layer = addTileLayer(project, 'library');
    project = paintTile(layer.project, 'library', layer.layerId, 3, 3, 7);
    project = resizeWorldScene(project, 'library', 8, 6);

    const library = project.scenes.find((scene) => scene.id === 'library');
    if (library?.type !== 'TOP_DOWN') throw new Error('expected library');
    expect([library.width, library.height]).toEqual([8, 6]);
    expect(library.tileLayers[0]?.data).toHaveLength(48);
    expect(library.tileLayers[0]?.data[3 * 8 + 3]).toBe(7);
    expect(library.objects.every((object) => object.position.x < 8 && object.position.y < 6)).toBe(true);
    expect(parseGameProject(project)).toBe(project);
  });

  it('blocks authoring map sizes that cannot be represented by the shared tile contract', () => {
    let project = createStarterProject(48);
    project = addPlatformerScene(project);
    const platformer = project.scenes.at(-1);
    if (platformer?.type !== 'PLATFORMER') throw new Error('expected platformer');

    expect(() => resizeWorldScene(project, platformer.id, 200, 100)).toThrow('10,000칸');
    const valid = resizeWorldScene(project, platformer.id, 200, 50);
    const resized = valid.scenes.find((scene) => scene.id === platformer.id);
    expect(resized?.type === 'PLATFORMER' ? [resized.width, resized.height] : null).toEqual([200, 50]);
    expect(parseGameProject(valid)).toBe(valid);
  });

  // 상한 guard가 addTileLayer가 아니라 removeObjects에 붙어 있던 회귀를 잠근다(#101).
  // 편집기 경로는 resizeWorldScene이 막으므로, 로드된 프로젝트처럼 상한을 넘는 scene을 직접 만들어 확인한다.
  it('blocks tile layers on oversized scenes and leaves object removal untouched', () => {
    let project = createStarterProject(48);
    project = addPlatformerScene(project);
    const platformer = project.scenes.at(-1);
    if (platformer?.type !== 'PLATFORMER') throw new Error('expected platformer');

    const oversized = {
      ...project,
      scenes: project.scenes.map((scene) => (
        scene.id === platformer.id && scene.type === 'PLATFORMER'
          ? { ...scene, width: 200, height: 100, tileLayers: [] }
          : scene
      )),
    };

    expect(() => addTileLayer(oversized, platformer.id)).toThrow('10,000칸');

    // 오브젝트 삭제는 타일 한도와 무관하다 — 같은 scene에서 막히지 않아야 한다.
    const spawn = platformer.objects.find((object) => object.preset === 'PLAYER_SPAWN');
    if (spawn === undefined) throw new Error('expected player spawn');
    expect(() => removeObjects(oversized, platformer.id, [spawn.id])).not.toThrow();
  });

  // S15P21A604-360 — Event Action 순서를 드래그(햄버거 핸들)로 바꿀 수 있게 한다.
  it('reorders event actions to an arbitrary position in one step, and leaves out-of-range/no-op moves untouched', () => {
    let project = createStarterProject(52);
    // takeLibraryKey: [GIVE_ITEM, HIDE_OBJECT] — 여기에 SET_VARIABLE을 하나 더 붙여
    // 인접 swap으로는 표현 못 하는 "여러 칸 건너뛰는 드래그"를 검증한다.
    project = appendEventAction(project, 'library', 'takeLibraryKey', 'SET_VARIABLE');

    // 3번째(index 2) action을 맨 앞(index 0)으로 — 드래그 한 번으로 두 칸을 건너뛴다.
    const moved = reorderEventAction(project, 'library', 'takeLibraryKey', 2, 0);
    const scene = moved.scenes.find((candidate) => candidate.id === 'library');
    if (scene?.type !== 'TOP_DOWN') throw new Error('expected library');
    const takeLibraryKey = scene.events.find((candidate) => candidate.id === 'takeLibraryKey');
    expect(takeLibraryKey?.actions.map((action) => action.type)).toEqual(['SET_VARIABLE', 'GIVE_ITEM', 'HIDE_OBJECT']);
    expect(parseGameProject(moved)).toBe(moved);

    // fromIndex === toIndex, 그리고 범위를 벗어난 인덱스는 아무것도 바꾸지 않아야 한다.
    const originalTypes = ['GIVE_ITEM', 'HIDE_OBJECT', 'SET_VARIABLE'];
    for (const [fromIndex, toIndex] of [[1, 1], [-1, 0], [0, 3]] as const) {
      const noop = reorderEventAction(project, 'library', 'takeLibraryKey', fromIndex, toIndex);
      const noopScene = noop.scenes.find((candidate) => candidate.id === 'library');
      if (noopScene?.type !== 'TOP_DOWN') throw new Error('expected library');
      expect(noopScene.events.find((candidate) => candidate.id === 'takeLibraryKey')?.actions.map((action) => action.type))
        .toEqual(originalTypes);
    }
  });

  // terminal Action(GO_TO_SCENE 등)은 배열의 마지막에만 있을 수 있다(event-runtime-semantics.md) —
  // reorderEventAction이 이 규칙을 어기면 validated()가 TERMINAL_ACTION_NOT_LAST로 막아야 한다.
  // EventEditor는 드롭 가능 최대 인덱스를 clamp해서 애초에 이 드롭이 발생하지 않게 막지만,
  // 이 계약 자체는 커맨드가 직접 지켜야 한다.
  it('rejects reordering that would move a terminal action out of the last position', () => {
    const project = createStarterProject(53);
    // openLockedDoor: [SET_VARIABLE, GO_TO_SCENE] — GO_TO_SCENE을 앞으로 옮기면 마지막 자리를 벗어난다.
    let error: unknown;
    try {
      reorderEventAction(project, 'library', 'openLockedDoor', 1, 0);
    } catch (caught) {
      error = caught;
    }
    expect(error).toBeInstanceOf(GameProjectContractError);
    expect((error as GameProjectContractError).code).toBe('TERMINAL_ACTION_NOT_LAST');
    expect(isTerminalActionType('GO_TO_SCENE')).toBe(true);
    expect(isTerminalActionType('SET_VARIABLE')).toBe(false);
  });

  // S15P21A604-361 — 편집기에서 시작 Scene을 지정/변경할 수 있게 한다.
  it('changes the start scene to a valid non-OVERLAY target and updates the delete guard accordingly', () => {
    const project = createStarterProject(54);
    expect(project.startSceneId).toBe('library');
    // 옮기기 전 'library'는 시작 Scene이라 지울 수 없다 — 삭제 가드가 여전히 startSceneId를 본다는
    // 걸 대조하기 위한 기준선이다.
    expect(sceneRemovalReason(project, 'library')).toBe('시작 Scene은 삭제할 수 없습니다.');

    const moved = setStartScene(project, 'ending');
    expect(moved.startSceneId).toBe('ending');
    expect(parseGameProject(moved)).toBe(moved);
    // 시작 Scene 삭제 가드는 startSceneId를 그대로 읽으므로, 옮기고 나면 이제 'ending'이 막히고
    // 예전 시작 Scene이었던 'library'는(다른 사유가 없다면) 더 이상 이 사유로 막히지 않아야 한다.
    expect(sceneRemovalReason(moved, 'ending')).toBe('시작 Scene은 삭제할 수 없습니다.');
    expect(sceneRemovalReason(moved, 'library')).toBeNull();
  });

  it('rejects setting an OVERLAY dialogue as the start scene, and reports the reason for both cases', () => {
    const project = createStarterProject(55);

    expect(startSceneChangeReason(project, 'library')).toBe('이미 시작 Scene입니다.');
    expect(startSceneChangeReason(project, 'librarianDialogue'))
      .toBe('게임 화면 위에 겹쳐 보이는 대화(OVERLAY)는 시작 Scene으로 지정할 수 없습니다.');
    expect(startSceneChangeReason(project, 'ending')).toBeNull();

    expect(() => setStartScene(project, 'librarianDialogue'))
      .toThrow('게임 화면 위에 겹쳐 보이는 대화(OVERLAY)는 시작 Scene으로 지정할 수 없습니다.');
  });

  // S15P21A604-494 — 씬 목록의 reorderScene/startSceneChangeReason·setStartScene/
  // sceneRemovalReason·removeScene을 대화 노드에도 그대로 미러링한다.
  describe('dialogue node management', () => {
    const buildDialogueScene = (gameId: number) => {
      let project = addDialogueScene(parseGameProject(createStarterProject(gameId)), 'OVERLAY');
      const sceneId = project.scenes.at(-1)?.id;
      if (sceneId === undefined) throw new Error('expected a newly added DIALOGUE scene');
      const initialScene = project.scenes.find((candidate) => candidate.id === sceneId);
      if (initialScene?.type !== 'DIALOGUE') throw new Error('expected DIALOGUE scene');
      const firstNodeId = initialScene.startNodeId;
      const second = addDialogueNode(project, sceneId);
      project = second.project;
      const third = addDialogueNode(project, sceneId);
      project = third.project;
      return { project, sceneId, firstNodeId, secondNodeId: second.nodeId, thirdNodeId: third.nodeId };
    };
    const nodeOrder = (project: ReturnType<typeof buildDialogueScene>['project'], sceneId: string) => {
      const scene = project.scenes.find((candidate) => candidate.id === sceneId);
      if (scene?.type !== 'DIALOGUE') throw new Error('expected DIALOGUE scene');
      return scene.nodes.map((node) => node.id);
    };

    it('reorders dialogue nodes, no-ops when already in place, and clamps out-of-range targets', () => {
      const { project, sceneId, firstNodeId, secondNodeId, thirdNodeId } = buildDialogueScene(60);
      expect(nodeOrder(project, sceneId)).toEqual([firstNodeId, secondNodeId, thirdNodeId]);

      const reordered = reorderDialogueNode(project, sceneId, thirdNodeId, 0);
      expect(nodeOrder(reordered, sceneId)).toEqual([thirdNodeId, firstNodeId, secondNodeId]);
      expect(parseGameProject(reordered)).toBe(reordered);

      expect(reorderDialogueNode(project, sceneId, firstNodeId, 0)).toBe(project);

      const clamped = reorderDialogueNode(project, sceneId, firstNodeId, 99);
      expect(nodeOrder(clamped, sceneId)).toEqual([secondNodeId, thirdNodeId, firstNodeId]);
    });

    it('changes the start node, and reports/blocks the already-start case', () => {
      const { project, sceneId, firstNodeId, secondNodeId } = buildDialogueScene(61);

      expect(startNodeChangeReason(project, sceneId, firstNodeId)).toBe('이미 시작 노드입니다.');
      expect(startNodeChangeReason(project, sceneId, secondNodeId)).toBeNull();

      const moved = setStartNode(project, sceneId, secondNodeId);
      const scene = moved.scenes.find((candidate) => candidate.id === sceneId);
      if (scene?.type !== 'DIALOGUE') throw new Error('expected DIALOGUE scene');
      expect(scene.startNodeId).toBe(secondNodeId);
      expect(parseGameProject(moved)).toBe(moved);

      expect(() => setStartNode(project, sceneId, firstNodeId)).toThrow('이미 시작 노드입니다.');
    });

    it('blocks removing the start node, a node referenced by another choice, or the last remaining node — and removes an eligible node otherwise', () => {
      const { project: base, sceneId, firstNodeId, secondNodeId, thirdNodeId } = buildDialogueScene(62);
      const withChoice = addDialogueChoice(base, sceneId, firstNodeId);
      const firstSceneWithChoice = withChoice.scenes.find((candidate) => candidate.id === sceneId);
      if (firstSceneWithChoice?.type !== 'DIALOGUE') throw new Error('expected DIALOGUE scene');
      const choiceId = firstSceneWithChoice.nodes.find((node) => node.id === firstNodeId)?.choices.at(-1)?.id;
      if (choiceId === undefined) throw new Error('expected a choice on the first node');
      // firstNode의 선택지가 secondNode를 가리키게 한다 — secondNode는 이제 "참조 중"이다.
      const project = updateDialogueChoice(withChoice, sceneId, firstNodeId, choiceId, (choice) => ({
        ...choice,
        nextNodeId: secondNodeId,
        actions: [],
      }));

      expect(dialogueNodeRemovalReason(project, sceneId, firstNodeId))
        .toBe('시작 노드는 삭제할 수 없습니다. 다른 노드를 시작으로 설정한 뒤 삭제하세요.');
      expect(() => removeDialogueNode(project, sceneId, firstNodeId)).toThrow();

      expect(dialogueNodeRemovalReason(project, sceneId, secondNodeId))
        .toBe('다른 노드의 선택지가 이 대화를 가리키고 있습니다.');
      expect(() => removeDialogueNode(project, sceneId, secondNodeId)).toThrow();

      expect(dialogueNodeRemovalReason(project, sceneId, thirdNodeId)).toBeNull();
      const afterRemoveThird = removeDialogueNode(project, sceneId, thirdNodeId);
      expect(nodeOrder(afterRemoveThird, sceneId)).toEqual([firstNodeId, secondNodeId]);
      expect(parseGameProject(afterRemoveThird)).toBe(afterRemoveThird);

      // 남은 노드가 1개가 될 때까지 줄여서, 참조/START 문제가 없어도 마지막 1개는 못 지우는지 확인한다.
      const choiceCleared = updateDialogueChoice(afterRemoveThird, sceneId, firstNodeId, choiceId, (choice) => ({
        ...choice,
        nextNodeId: undefined,
        actions: [{ type: 'CLOSE_DIALOGUE' }],
      }));
      const secondIsStart = setStartNode(choiceCleared, sceneId, secondNodeId);
      const onlySecondLeft = removeDialogueNode(secondIsStart, sceneId, firstNodeId);
      expect(nodeOrder(onlySecondLeft, sceneId)).toEqual([secondNodeId]);

      expect(dialogueNodeRemovalReason(onlySecondLeft, sceneId, secondNodeId))
        .toBe('대화에는 최소 1개의 노드가 필요합니다.');
      expect(() => removeDialogueNode(onlySecondLeft, sceneId, secondNodeId)).toThrow();
    });
  });
});

// S15P21A604-529 — 오브젝트 이름/플레이 중 표시 여부. PLAYER_SPAWN은 플레이 중 화면에
// 렌더링되지 않는 마커라 이름/표시 설정 자체를 둘 수 없다(QA 확정 사항) — 명령이 조용히
// no-op으로 무시하는지까지 확인한다.
describe('오브젝트 이름 및 표시 설정(S15P21A604-529)', () => {
  const findObject = (project: ReturnType<typeof createStarterProject>, objectId: string) => {
    const library = project.scenes.find((scene) => scene.id === 'library');
    if (library?.type !== 'TOP_DOWN') throw new Error('expected library');
    return library.objects.find((candidate) => candidate.id === objectId);
  };

  it('새로 배치한 오브젝트는 이름/표시 설정이 없다(기본값)', () => {
    const project = createStarterProject(529);
    const placed = addObject(project, 'library', 'NPC', { x: 3, y: 3 });
    const object = findObject(placed.project, placed.objectId);
    expect(object?.name).toBeUndefined();
    expect(object?.showNameInPlay).toBeUndefined();
  });

  it('renameObject로 이름을 지으면 저장되고 앞뒤 공백은 trim된다', () => {
    const project = createStarterProject(529);
    const placed = addObject(project, 'library', 'NPC', { x: 3, y: 3 });
    const renamed = renameObject(placed.project, 'library', placed.objectId, '  사서  ');
    expect(findObject(renamed, placed.objectId)?.name).toBe('사서');
    expect(parseGameProject(renamed)).toBe(renamed);
  });

  it('이름을 빈 문자열로 지우면 name이 사라진다(빈 문자열로 저장되지 않는다)', () => {
    const project = createStarterProject(529);
    const placed = addObject(project, 'library', 'NPC', { x: 3, y: 3 });
    const renamed = renameObject(placed.project, 'library', placed.objectId, '사서');
    const cleared = renameObject(renamed, 'library', placed.objectId, '   ');
    expect(findObject(cleared, placed.objectId)?.name).toBeUndefined();
    expect(parseGameProject(cleared)).toBe(cleared);
  });

  it('setObjectNameVisible로 플레이 중 표시 여부를 토글할 수 있다', () => {
    const project = createStarterProject(529);
    const placed = addObject(project, 'library', 'NPC', { x: 3, y: 3 });
    const shown = setObjectNameVisible(placed.project, 'library', placed.objectId, true);
    expect(findObject(shown, placed.objectId)?.showNameInPlay).toBe(true);
    const hidden = setObjectNameVisible(shown, 'library', placed.objectId, false);
    expect(findObject(hidden, placed.objectId)?.showNameInPlay).toBe(false);
  });

  it('PLAYER_SPAWN 오브젝트에는 이름/표시 설정을 할 수 없다(조용히 무시)', () => {
    const project = createStarterProject(529);
    const library = project.scenes.find((scene) => scene.id === 'library');
    if (library?.type !== 'TOP_DOWN') throw new Error('expected library');
    const spawnId = library.objects.find((object) => object.preset === 'PLAYER_SPAWN')?.id;
    if (spawnId === undefined) throw new Error('expected a PLAYER_SPAWN object');

    const afterRename = renameObject(project, 'library', spawnId, '주인공');
    expect(findObject(afterRename, spawnId)?.name).toBeUndefined();

    const afterVisible = setObjectNameVisible(project, 'library', spawnId, true);
    expect(findObject(afterVisible, spawnId)?.showNameInPlay).toBeUndefined();
  });
});

describe('변수 삭제(S15P21A604-562)', () => {
  it('참조되지 않는 변수는 지울 수 있다', () => {
    const project = addBooleanVariable(createStarterProject(562));
    const variableId = project.variables[project.variables.length - 1]!.id;

    const removed = removeVariableDefinition(project, variableId);

    expect(removed.variables.some((variable) => variable.id === variableId)).toBe(false);
    expect(parseGameProject(removed)).toBe(removed);
  });

  it('조건·액션에서 참조 중인 변수를 지우면 검증이 막는다', () => {
    // 스타터 프로젝트의 기본 변수 doorOpened는 library Scene의 이벤트가 이미
    // VARIABLE_EQUALS 조건과 SET_VARIABLE 액션 양쪽에서 참조하고 있다.
    const project = createStarterProject(563);

    expect(() => removeVariableDefinition(project, 'doorOpened')).toThrow(GameProjectContractError);
    let error: unknown;
    try {
      removeVariableDefinition(project, 'doorOpened');
    } catch (thrown) {
      error = thrown;
    }
    expect((error as GameProjectContractError).code).toBe('VARIABLE_REFERENCE_NOT_FOUND');
  });
});

describe('아이템 삭제(S15P21A604-565)', () => {
  it('참조되지 않는 아이템은 지울 수 있다', () => {
    const project = addItemDefinition(createStarterProject(565));
    const itemId = project.items[project.items.length - 1]!.id;

    const removed = removeItemDefinition(project, itemId);

    expect(removed.items.some((item) => item.id === itemId)).toBe(false);
    expect(parseGameProject(removed)).toBe(removed);
  });

  it('오브젝트의 PICKUP 컴포넌트에서 참조 중인 아이템을 지우면 검증이 막는다', () => {
    // 스타터 프로젝트의 기본 아이템 libraryKey는 library Scene의 오브젝트가
    // PICKUP 컴포넌트로 들고 있고, 이벤트도 GIVE_ITEM/HAS_ITEM으로 참조한다.
    const project = createStarterProject(566);

    expect(() => removeItemDefinition(project, 'libraryKey')).toThrow(GameProjectContractError);
    let error: unknown;
    try {
      removeItemDefinition(project, 'libraryKey');
    } catch (thrown) {
      error = thrown;
    }
    expect((error as GameProjectContractError).code).toBe('PICKUP_ITEM_NOT_FOUND');
  });
});
