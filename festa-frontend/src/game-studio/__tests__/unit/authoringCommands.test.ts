import { describe, expect, it } from 'vitest';
import { parseGameProject } from '../../contracts/gameProject.ts';
import {
  addComponent,
  addDialogueScene,
  addObject,
  addObjectEvent,
  addPlatformerScene,
  addTileLayer,
  addTopDownScene,
  appendEventAction,
  appendEventCondition,
  copyObjectsToScene,
  duplicateScene,
  duplicateObjects,
  fillTileLayer,
  floodFillTiles,
  moveObject,
  moveObjects,
  moveScene,
  paintTile,
  paintTiles,
  removeObjects,
  resizeWorldScene,
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
});
