import { describe, expect, it } from 'vitest';
import { parseGameProject } from '../../contracts/gameProject.ts';
import {
  addComponent,
  addDialogueScene,
  addObject,
  addObjectEvent,
  addTileLayer,
  addTopDownScene,
  appendEventAction,
  appendEventCondition,
  fillTileLayer,
  moveObject,
  paintTile,
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

    const library = project.scenes.find((scene) => scene.id === 'library');
    if (library?.type !== 'TOP_DOWN') throw new Error('expected library');
    const data = library.tileLayers[0]?.data;
    expect(data).toHaveLength(library.width * library.height);
    expect(data?.[4 * library.width + 2]).toBe(7);
    expect(data?.[0]).toBe(-1);
    expect(parseGameProject(project)).toBe(project);
  });
});
