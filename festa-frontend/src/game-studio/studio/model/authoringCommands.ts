import {
  GAME_PROJECT_LIMITS,
  DEFAULT_GAME_RULES,
  parseGameProject,
  type Action,
  type AssetReference,
  type Component,
  type Condition,
  type DialogueChoice,
  type DialogueNode,
  type DialogueScene,
  type GameEvent,
  type GameObject,
  type GameRules,
  type GameProject,
  type TopDownScene,
  type PlatformerScene,
  type WorldScene,
  type Trigger,
} from '../../contracts/gameProject.ts';
import { findPresetDefinition } from './authoringRegistry.ts';
import { BUILTIN_PROJECT_ASSETS } from '../assets/builtinAssetCatalog.ts';

const terminalActionTypes = new Set<Action['type']>([
  'SHOW_DIALOGUE',
  'CLOSE_DIALOGUE',
  'GO_TO_SCENE',
  'COMPLETE_GAME',
]);

const validated = (project: GameProject): GameProject => parseGameProject(project);

export const withBuiltinAssetLibrary = (project: GameProject): GameProject => {
  const upgraded: GameProject = project.schemaVersion === '1.0.0'
    ? { ...project, schemaVersion: '1.1.0', rules: DEFAULT_GAME_RULES }
    : project;
  const sources = new Set(upgraded.assets.map((asset) => asset.source));
  const missing = BUILTIN_PROJECT_ASSETS.filter((asset) => !sources.has(asset.source));
  return validated(missing.length === 0 ? upgraded : { ...upgraded, assets: [...upgraded.assets, ...missing] });
};

export const replaceGameRules = (project: GameProject, rules: GameRules): GameProject => validated({
  ...project,
  schemaVersion: '1.1.0',
  rules,
});

const allIds = (project: GameProject): Set<string> => new Set([
  ...project.scenes.map((scene) => scene.id),
  ...project.scenes.flatMap((scene) => scene.type !== 'DIALOGUE'
    ? [...scene.objects.map((object) => object.id), ...scene.events.map((event) => event.id)]
    : [
        ...scene.nodes.map((node) => node.id),
        ...scene.nodes.flatMap((node) => node.choices.map((choice) => choice.id)),
      ]),
  ...project.variables.map((variable) => variable.id),
  ...project.items.map((item) => item.id),
  ...project.assets.map((asset) => asset.id),
]);

export const nextStableId = (project: GameProject, prefix: string): string => {
  const used = allIds(project);
  let suffix = 1;
  while (used.has(`${prefix}${suffix}`)) suffix += 1;
  return `${prefix}${suffix}`;
};

const replaceScene = (
  project: GameProject,
  sceneId: string,
  updater: (scene: GameProject['scenes'][number]) => GameProject['scenes'][number],
): GameProject => validated({
  ...project,
  scenes: project.scenes.map((scene) => scene.id === sceneId ? updater(scene) : scene),
});

const replaceTopDownScene = (
  project: GameProject,
  sceneId: string,
  updater: (scene: WorldScene) => WorldScene,
): GameProject => replaceScene(project, sceneId, (scene) => {
  if (scene.type === 'DIALOGUE') throw new Error(`${sceneId} is not a world scene`);
  return updater(scene);
});

const replaceDialogueScene = (
  project: GameProject,
  sceneId: string,
  updater: (scene: DialogueScene) => DialogueScene,
): GameProject => replaceScene(project, sceneId, (scene) => {
  if (scene.type !== 'DIALOGUE') throw new Error(`${sceneId} is not a DIALOGUE scene`);
  return updater(scene);
});

export const renameProject = (project: GameProject, title: string): GameProject => (
  validated({ ...project, title: title.trim() })
);

export const renameScene = (
  project: GameProject,
  sceneId: string,
  name: string,
): GameProject => replaceScene(project, sceneId, (scene) => ({ ...scene, name: name.trim() }));

export const setTopDownBackground = (
  project: GameProject,
  sceneId: string,
  assetId: string | undefined,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({ ...scene, backgroundAssetId: assetId }));

export const setDialogueBackground = (
  project: GameProject,
  sceneId: string,
  assetId: string | undefined,
): GameProject => replaceDialogueScene(project, sceneId, (scene) => ({ ...scene, backgroundAssetId: assetId }));

export const addTopDownScene = (project: GameProject): GameProject => {
  const sceneId = nextStableId(project, 'map');
  const spawnId = nextStableId(project, 'playerSpawn');
  const scene: TopDownScene = {
    id: sceneId,
    type: 'TOP_DOWN',
    name: `새 맵 ${project.scenes.filter((candidate) => candidate.type === 'TOP_DOWN').length + 1}`,
    width: 16,
    height: 10,
    tileLayers: [],
    objects: [{
      id: spawnId,
      preset: 'PLAYER_SPAWN',
      position: { x: 8, y: 8 },
      visible: true,
      components: [],
    }],
    events: [],
  };
  return validated({ ...project, scenes: [...project.scenes, scene] });
};

export const addPlatformerScene = (project: GameProject): GameProject => {
  const sceneId = nextStableId(project, 'platform');
  const spawnId = nextStableId(project, 'playerSpawn');
  const platformAsset = project.assets.find((asset) => asset.source === 'builtin://sprites/platform');
  const goalAsset = project.assets.find((asset) => asset.source === 'builtin://sprites/goal');
  const floorObjects: GameObject[] = Array.from({ length: 24 }, (_, x) => ({
    id: `${sceneId}Floor${x}`,
    preset: 'PLATFORM',
    position: { x, y: 11 },
    visible: true,
    components: [
      ...(platformAsset === undefined ? [] : [{ type: 'SPRITE' as const, assetId: platformAsset.id, scale: 100, zIndex: 1 }]),
      { type: 'COLLIDER' as const, solid: true },
    ],
  }));
  const goalId = `${sceneId}Goal`;
  const scene: PlatformerScene = {
    id: sceneId,
    type: 'PLATFORMER',
    name: `새 플랫폼 맵 ${project.scenes.filter((candidate) => candidate.type === 'PLATFORMER').length + 1}`,
    width: 24,
    height: 12,
    gravity: 12,
    tileLayers: [],
    objects: [
      {
        id: spawnId,
        preset: 'PLAYER_SPAWN',
        position: { x: 2, y: 10 },
        visible: true,
        components: [],
      },
      ...floorObjects,
      {
        id: goalId,
        preset: 'GOAL',
        position: { x: 21, y: 10 },
        visible: true,
        components: [
          ...(goalAsset === undefined ? [] : [{ type: 'SPRITE' as const, assetId: goalAsset.id, scale: 100, zIndex: 2 }]),
        ],
      },
    ],
    events: [{ id: `${sceneId}Complete`, trigger: { type: 'ON_ENTER', targetId: goalId }, conditions: [], actions: [{ type: 'COMPLETE_GAME' }] }],
  };
  return validated({ ...project, scenes: [...project.scenes, scene] });
};

export const addDialogueScene = (
  project: GameProject,
  presentation: DialogueScene['presentation'] = 'OVERLAY',
): GameProject => {
  const sceneId = nextStableId(project, 'dialogue');
  const nodeId = nextStableId(project, 'node');
  const choiceId = nextStableId(project, 'choice');
  const terminalAction: Action = presentation === 'OVERLAY'
    ? { type: 'CLOSE_DIALOGUE' }
    : { type: 'COMPLETE_GAME' };
  const scene: DialogueScene = {
    id: sceneId,
    type: 'DIALOGUE',
    name: presentation === 'OVERLAY' ? '새 대화' : '새 이야기 장면',
    presentation,
    startNodeId: nodeId,
    nodes: [{
      id: nodeId,
      speaker: '인물',
      text: '대사를 입력하세요.',
      choices: [{ id: choiceId, text: '계속', actions: [terminalAction] }],
    }],
  };
  return validated({ ...project, scenes: [...project.scenes, scene] });
};

export const sceneRemovalReason = (project: GameProject, sceneId: string): string | null => {
  if (project.scenes.length === 1) return '프로젝트에는 Scene이 하나 이상 필요합니다.';
  if (project.startSceneId === sceneId) return '시작 Scene은 삭제할 수 없습니다.';
  const referenced = project.scenes.some((scene) => {
    if (scene.type !== 'DIALOGUE') {
      return scene.events.some((event) => event.actions.some((action) => (
        (action.type === 'GO_TO_SCENE' || action.type === 'SHOW_DIALOGUE') && action.sceneId === sceneId
      )));
    }
    return scene.nodes.some((node) => node.choices.some((choice) => choice.actions.some((action) => (
      (action.type === 'GO_TO_SCENE' || action.type === 'SHOW_DIALOGUE') && action.sceneId === sceneId
    ))));
  });
  return referenced ? '다른 Event가 이 Scene을 참조하고 있습니다.' : null;
};

export const removeScene = (project: GameProject, sceneId: string): GameProject => {
  const reason = sceneRemovalReason(project, sceneId);
  if (reason !== null) throw new Error(reason);
  return validated({ ...project, scenes: project.scenes.filter((scene) => scene.id !== sceneId) });
};

const remapCopiedAction = (
  action: Action,
  sourceSceneId: string,
  copiedSceneId: string,
  objectIdMap: ReadonlyMap<string, string>,
): Action => {
  if ((action.type === 'SHOW_OBJECT' || action.type === 'HIDE_OBJECT') && objectIdMap.has(action.objectId)) {
    return { ...action, objectId: objectIdMap.get(action.objectId) ?? action.objectId };
  }
  if ((action.type === 'SHOW_DIALOGUE' || action.type === 'GO_TO_SCENE') && action.sceneId === sourceSceneId) {
    return { ...action, sceneId: copiedSceneId };
  }
  return { ...action };
};

export const duplicateScene = (
  project: GameProject,
  sceneId: string,
): { readonly project: GameProject; readonly sceneId: string } => {
  const source = project.scenes.find((scene) => scene.id === sceneId);
  if (source === undefined) throw new Error(`${sceneId} Scene을 찾을 수 없습니다.`);
  if (project.scenes.length >= GAME_PROJECT_LIMITS.maxScenes) {
    throw new Error(`프로젝트에는 Scene을 ${GAME_PROJECT_LIMITS.maxScenes}개까지만 만들 수 있습니다.`);
  }

  const usedIds = allIds(project);
  const copiedSceneId = nextUniqueId(usedIds, source.type === 'DIALOGUE' ? 'dialogue' : source.type === 'PLATFORMER' ? 'platform' : 'map');
  let copiedScene: GameProject['scenes'][number];

  if (source.type === 'DIALOGUE') {
    const nodeIdMap = new Map(source.nodes.map((node) => [node.id, nextUniqueId(usedIds, 'node')]));
    const choiceIdMap = new Map(source.nodes.flatMap((node) => (
      node.choices.map((choice) => [choice.id, nextUniqueId(usedIds, 'choice')] as const)
    )));
    copiedScene = {
      ...source,
      id: copiedSceneId,
      name: `${source.name} 복사본`,
      startNodeId: nodeIdMap.get(source.startNodeId) ?? source.startNodeId,
      nodes: source.nodes.map((node) => ({
        ...node,
        id: nodeIdMap.get(node.id) ?? node.id,
        choices: node.choices.map((choice) => ({
          ...choice,
          id: choiceIdMap.get(choice.id) ?? choice.id,
          nextNodeId: choice.nextNodeId === undefined ? undefined : nodeIdMap.get(choice.nextNodeId) ?? choice.nextNodeId,
          conditions: choice.conditions?.map((condition) => ({ ...condition })),
          actions: choice.actions.map((action) => remapCopiedAction(action, source.id, copiedSceneId, new Map())),
        })),
      })),
    };
  } else {
    const objectIdMap = new Map(source.objects.map((object) => [
      object.id,
      nextUniqueId(usedIds, object.preset === 'PLAYER_SPAWN' ? 'playerSpawn' : 'object'),
    ]));
    copiedScene = {
      ...source,
      id: copiedSceneId,
      name: `${source.name} 복사본`,
      tileLayers: source.tileLayers.map((layer) => ({ ...layer, data: [...layer.data] })),
      objects: source.objects.map((object) => ({
        ...object,
        id: objectIdMap.get(object.id) ?? object.id,
        position: { ...object.position },
        components: object.components.map((component) => ({ ...component })),
      })),
      events: source.events.map((event) => ({
        ...event,
        id: nextUniqueId(usedIds, 'event'),
        trigger: event.trigger.type === 'ON_SCENE_START'
          ? { ...event.trigger }
          : { ...event.trigger, targetId: objectIdMap.get(event.trigger.targetId) ?? event.trigger.targetId },
        conditions: event.conditions.map((condition) => ({ ...condition })),
        actions: event.actions.map((action) => remapCopiedAction(action, source.id, copiedSceneId, objectIdMap)),
      })),
    };
  }

  const sourceIndex = project.scenes.findIndex((scene) => scene.id === sceneId);
  const scenes = [...project.scenes];
  scenes.splice(sourceIndex + 1, 0, copiedScene);
  return { project: validated({ ...project, scenes }), sceneId: copiedSceneId };
};

export const moveScene = (project: GameProject, sceneId: string, offset: -1 | 1): GameProject => {
  const sourceIndex = project.scenes.findIndex((scene) => scene.id === sceneId);
  if (sourceIndex < 0) throw new Error(`${sceneId} Scene을 찾을 수 없습니다.`);
  const targetIndex = Math.max(0, Math.min(project.scenes.length - 1, sourceIndex + offset));
  if (targetIndex === sourceIndex) return project;
  const scenes = [...project.scenes];
  const [scene] = scenes.splice(sourceIndex, 1);
  if (scene === undefined) return project;
  scenes.splice(targetIndex, 0, scene);
  return validated({ ...project, scenes });
};

export const addObject = (
  project: GameProject,
  sceneId: string,
  preset: GameObject['preset'],
  position: GameObject['position'],
): { readonly project: GameProject; readonly objectId: string } => {
  const scene = project.scenes.find((candidate) => candidate.id === sceneId);
  if (scene?.type === 'DIALOGUE' || scene === undefined) throw new Error(`${sceneId} is not a world scene`);
  if (preset === 'PLAYER_SPAWN' && scene.objects.some((object) => object.preset === 'PLAYER_SPAWN')) {
    throw new Error('플레이 장면에는 플레이어 시작점을 하나만 둘 수 있습니다.');
  }
  const objectId = nextStableId(project, preset === 'PLAYER_SPAWN' ? 'playerSpawn' : 'object');
  const object: GameObject = {
    id: objectId,
    preset,
    position: {
      x: Math.max(0, Math.min(scene.width - 1, Math.round(position.x))),
      y: Math.max(0, Math.min(scene.height - 1, Math.round(position.y))),
    },
    visible: true,
    components: findPresetDefinition(preset).createComponents(project),
  };
  return {
    project: replaceTopDownScene(project, sceneId, (current) => ({
      ...current,
      objects: [...current.objects, object],
    })),
    objectId,
  };
};

export const moveObject = (
  project: GameProject,
  sceneId: string,
  objectId: string,
  x: number,
  y: number,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  objects: scene.objects.map((object) => object.id === objectId ? {
    ...object,
    position: {
      x: Math.max(0, Math.min(scene.width - 1, Math.round(x))),
      y: Math.max(0, Math.min(scene.height - 1, Math.round(y))),
    },
  } : object),
}));

export const moveObjects = (
  project: GameProject,
  sceneId: string,
  objectIds: readonly string[],
  deltaX: number,
  deltaY: number,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => {
  const selectedIds = new Set(objectIds);
  const selected = scene.objects.filter((object) => selectedIds.has(object.id));
  if (selected.length === 0) return scene;
  const requestedX = Math.round(deltaX);
  const requestedY = Math.round(deltaY);
  const minX = Math.min(...selected.map((object) => object.position.x));
  const maxX = Math.max(...selected.map((object) => object.position.x));
  const minY = Math.min(...selected.map((object) => object.position.y));
  const maxY = Math.max(...selected.map((object) => object.position.y));
  const appliedX = Math.max(-minX, Math.min(scene.width - 1 - maxX, requestedX));
  const appliedY = Math.max(-minY, Math.min(scene.height - 1 - maxY, requestedY));
  return {
    ...scene,
    objects: scene.objects.map((object) => selectedIds.has(object.id) ? {
      ...object,
      position: { x: object.position.x + appliedX, y: object.position.y + appliedY },
    } : object),
  };
});

const nextUniqueId = (usedIds: Set<string>, prefix: string): string => {
  let suffix = 1;
  while (usedIds.has(`${prefix}${suffix}`)) suffix += 1;
  const id = `${prefix}${suffix}`;
  usedIds.add(id);
  return id;
};

export const copyObjectsToScene = (
  project: GameProject,
  sourceSceneId: string,
  targetSceneId: string,
  objectIds: readonly string[],
): { readonly project: GameProject; readonly objectIds: readonly string[] } => {
  const sourceScene = project.scenes.find((candidate) => candidate.id === sourceSceneId);
  const targetScene = project.scenes.find((candidate) => candidate.id === targetSceneId);
  if (sourceScene?.type === 'DIALOGUE' || sourceScene === undefined) throw new Error(`${sourceSceneId} is not a world scene`);
  if (targetScene?.type === 'DIALOGUE' || targetScene === undefined) throw new Error('오브젝트는 탐색 맵이나 플랫폼 맵에만 붙여넣을 수 있습니다.');
  const requestedIds = new Set(objectIds);
  const sourceObjects = sourceScene.objects.filter((object) => (
    requestedIds.has(object.id) && object.preset !== 'PLAYER_SPAWN'
  ));
  if (sourceObjects.length === 0) throw new Error('복제할 수 있는 오브젝트를 선택해 주세요.');
  if (targetScene.objects.length + sourceObjects.length > GAME_PROJECT_LIMITS.maxObjectsPerScene) {
    throw new Error(`Scene에는 오브젝트를 ${GAME_PROJECT_LIMITS.maxObjectsPerScene}개까지만 둘 수 있습니다.`);
  }

  const usedIds = allIds(project);
  const objectIdMap = new Map(sourceObjects.map((object) => [object.id, nextUniqueId(usedIds, 'object')]));
  const maxX = Math.max(...sourceObjects.map((object) => object.position.x));
  const maxY = Math.max(...sourceObjects.map((object) => object.position.y));
  const minX = Math.min(...sourceObjects.map((object) => object.position.x));
  const minY = Math.min(...sourceObjects.map((object) => object.position.y));
  const formationWidth = maxX - minX + 1;
  const formationHeight = maxY - minY + 1;
  if (formationWidth > targetScene.width || formationHeight > targetScene.height) {
    throw new Error('선택한 오브젝트 묶음이 대상 Scene보다 큽니다. 맵 크기를 먼저 늘려 주세요.');
  }
  const sameScene = sourceSceneId === targetSceneId;
  const targetMinX = sameScene
    ? minX + (maxX < targetScene.width - 1 ? 1 : minX > 0 ? -1 : 0)
    : Math.min(1, targetScene.width - formationWidth);
  const targetMinY = sameScene
    ? minY + (maxY < targetScene.height - 1 ? 1 : minY > 0 ? -1 : 0)
    : Math.min(1, targetScene.height - formationHeight);
  const duplicates: GameObject[] = sourceObjects.map((object) => ({
    ...object,
    id: objectIdMap.get(object.id) ?? object.id,
    position: { x: object.position.x - minX + targetMinX, y: object.position.y - minY + targetMinY },
    components: object.components.map((component) => ({ ...component })),
  }));
  const copiedEvents = sourceScene.events
    .filter((event) => event.trigger.type !== 'ON_SCENE_START' && objectIdMap.has(event.trigger.targetId))
    .map((event): GameEvent => ({
      ...event,
      id: nextUniqueId(usedIds, 'event'),
      trigger: event.trigger.type === 'ON_SCENE_START'
        ? event.trigger
        : { ...event.trigger, targetId: objectIdMap.get(event.trigger.targetId) ?? event.trigger.targetId },
      conditions: event.conditions.map((condition) => ({ ...condition })),
      actions: event.actions.map((action) => (
        (action.type === 'SHOW_OBJECT' || action.type === 'HIDE_OBJECT') && objectIdMap.has(action.objectId)
          ? { ...action, objectId: objectIdMap.get(action.objectId) ?? action.objectId }
          : { ...action }
      )),
    }));
  if (targetScene.events.length + copiedEvents.length > GAME_PROJECT_LIMITS.maxEventsPerScene) {
    throw new Error(`동작을 포함해 복제하면 Event ${GAME_PROJECT_LIMITS.maxEventsPerScene}개 제한을 넘습니다.`);
  }
  return {
    objectIds: duplicates.map((object) => object.id),
    project: replaceTopDownScene(project, targetSceneId, (current) => ({
      ...current,
      objects: [...current.objects, ...duplicates],
      events: [...current.events, ...copiedEvents],
    })),
  };
};

export const duplicateObjects = (
  project: GameProject,
  sceneId: string,
  objectIds: readonly string[],
): { readonly project: GameProject; readonly objectIds: readonly string[] } => (
  copyObjectsToScene(project, sceneId, sceneId, objectIds)
);

export const resizeWorldScene = (
  project: GameProject,
  sceneId: string,
  width: number,
  height: number,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => {
  const minWidth = scene.type === 'PLATFORMER' ? 8 : 4;
  const nextWidth = Math.max(minWidth, Math.min(scene.type === 'PLATFORMER' ? 200 : 100, Math.round(width)));
  const nextHeight = Math.max(scene.type === 'PLATFORMER' ? 6 : 4, Math.min(100, Math.round(height)));
  if (nextWidth * nextHeight > GAME_PROJECT_LIMITS.maxTileCellsPerLayer) {
    throw new Error(`맵은 타일 저장 한도인 ${GAME_PROJECT_LIMITS.maxTileCellsPerLayer.toLocaleString('ko-KR')}칸까지 만들 수 있습니다.`);
  }
  return {
    ...scene,
    width: nextWidth,
    height: nextHeight,
    tileLayers: scene.tileLayers.map((layer) => ({
      ...layer,
      data: Array.from({ length: nextWidth * nextHeight }, (_, index) => {
        const x = index % nextWidth;
        const y = Math.floor(index / nextWidth);
        return x < scene.width && y < scene.height ? (layer.data[y * scene.width + x] ?? -1) : -1;
      }),
    })),
    objects: scene.objects.map((object) => ({
      ...object,
      position: {
        x: Math.min(nextWidth - 1, object.position.x),
        y: Math.min(nextHeight - 1, object.position.y),
      },
    })),
  };
});

export const setObjectVisible = (
  project: GameProject,
  sceneId: string,
  objectId: string,
  visible: boolean,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  objects: scene.objects.map((object) => object.id === objectId ? { ...object, visible } : object),
}));

const objectReferencedByAction = (
  project: GameProject,
  objectId: string,
  removingIds: ReadonlySet<string> = new Set(),
): boolean => (
  project.scenes.some((scene) => {
    const actions = scene.type !== 'DIALOGUE'
      ? scene.events.flatMap((event) => (
        event.trigger.type !== 'ON_SCENE_START' && (event.trigger.targetId === objectId || removingIds.has(event.trigger.targetId))
          ? []
          : event.actions
      ))
      : scene.nodes.flatMap((node) => node.choices.flatMap((choice) => choice.actions));
    return actions.some((action) => (
      (action.type === 'SHOW_OBJECT' || action.type === 'HIDE_OBJECT') && action.objectId === objectId
    ));
  })
);

export const objectRemovalReason = (project: GameProject, objectId: string): string | null => {
  const object = project.scenes
    .flatMap((scene) => scene.type !== 'DIALOGUE' ? scene.objects : [])
    .find((candidate) => candidate.id === objectId);
  if (object?.preset === 'PLAYER_SPAWN') return '플레이어 시작점은 삭제할 수 없습니다.';
  return objectReferencedByAction(project, objectId)
    ? '다른 Event Action이 이 오브젝트를 참조하고 있습니다.'
    : null;
};

export const removeObject = (
  project: GameProject,
  sceneId: string,
  objectId: string,
): GameProject => {
  const reason = objectRemovalReason(project, objectId);
  if (reason !== null) throw new Error(reason);
  return replaceTopDownScene(project, sceneId, (scene) => ({
    ...scene,
    objects: scene.objects.filter((object) => object.id !== objectId),
    events: scene.events.filter((event) => (
      event.trigger.type === 'ON_SCENE_START' || event.trigger.targetId !== objectId
    )),
  }));
};

export const removeObjects = (
  project: GameProject,
  sceneId: string,
  objectIds: readonly string[],
): { readonly project: GameProject; readonly removedObjectIds: readonly string[]; readonly blocked: readonly { readonly objectId: string; readonly reason: string }[] } => {
  const scene = project.scenes.find((candidate) => candidate.id === sceneId);
  if (scene?.type === 'DIALOGUE' || scene === undefined) throw new Error(`${sceneId} is not a world scene`);
  if (scene.width * scene.height > GAME_PROJECT_LIMITS.maxTileCellsPerLayer) {
    throw new Error(`Tile Layer는 맵 크기를 ${GAME_PROJECT_LIMITS.maxTileCellsPerLayer.toLocaleString('ko-KR')}칸 이하로 줄인 뒤 추가할 수 있습니다.`);
  }
  const requestedIds = new Set(objectIds);
  const blocked = scene.objects.flatMap((object) => {
    if (!requestedIds.has(object.id)) return [];
    const reason = object.preset === 'PLAYER_SPAWN'
      ? '플레이어 시작점은 삭제할 수 없습니다.'
      : objectReferencedByAction(project, object.id, requestedIds)
        ? '선택 밖의 다른 Event Action이 이 오브젝트를 참조하고 있습니다.'
        : null;
    return reason === null ? [] : [{ objectId: object.id, reason }];
  });
  const blockedIds = new Set(blocked.map((entry) => entry.objectId));
  const removedObjectIds = scene.objects
    .filter((object) => requestedIds.has(object.id) && !blockedIds.has(object.id))
    .map((object) => object.id);
  const removedIds = new Set(removedObjectIds);
  if (removedIds.size === 0) return { project, removedObjectIds, blocked };
  return {
    blocked,
    removedObjectIds,
    project: replaceTopDownScene(project, sceneId, (current) => ({
      ...current,
      objects: current.objects.filter((object) => !removedIds.has(object.id)),
      events: current.events.filter((event) => (
        event.trigger.type === 'ON_SCENE_START' || !removedIds.has(event.trigger.targetId)
      )),
    })),
  };
};

export const createComponent = (
  project: GameProject,
  type: Component['type'],
): Component | null => {
  if (type === 'SPRITE') {
    const asset = project.assets.find((candidate) => candidate.kind === 'IMAGE');
    return asset === undefined ? null : { type, assetId: asset.id };
  }
  if (type === 'COLLIDER') return { type, solid: true };
  if (type === 'INTERACTABLE') return { type, prompt: '상호작용하기' };
  if (type === 'DAMAGE') return { type, amount: 1 };
  if (type === 'HEALTH') return { type, max: 3 };
  if (type === 'SCORE_VALUE') return { type, value: 100 };
  if (type === 'CHECKPOINT') return { type };
  if (type === 'AUTO_MOVE') return { type, axis: 'HORIZONTAL', speed: 2, range: 4 };
  if (type === 'SHOOTER') {
    const projectile = project.assets.find((candidate) => candidate.source === 'builtin://sprites/projectile-fireball');
    return projectile === undefined ? null : { type, projectileAssetId: projectile.id, damage: 1, cooldownMs: 900 };
  }
  if (type === 'SPAWNER') {
    const enemy = project.assets.find((candidate) => candidate.source === 'builtin://sprites/enemy-slime');
    return enemy === undefined ? null : { type, enemyAssetId: enemy.id, intervalMs: 3000, maxAlive: 5 };
  }
  const item = project.items[0];
  return item === undefined ? null : { type, itemId: item.id };
};

export const addComponent = (
  project: GameProject,
  sceneId: string,
  objectId: string,
  type: Component['type'],
): GameProject => {
  const component = createComponent(project, type);
  if (component === null) throw new Error(`${type} Component에 연결할 데이터가 없습니다.`);
  return replaceTopDownScene(project, sceneId, (scene) => ({
    ...scene,
    objects: scene.objects.map((object) => {
      if (object.id !== objectId || object.components.some((candidate) => candidate.type === type)) return object;
      return { ...object, components: [...object.components, component] };
    }),
  }));
};

export const replaceComponent = (
  project: GameProject,
  sceneId: string,
  objectId: string,
  component: Component,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  objects: scene.objects.map((object) => object.id === objectId ? {
    ...object,
    components: object.components.map((candidate) => candidate.type === component.type ? component : candidate),
  } : object),
}));

export const removeComponent = (
  project: GameProject,
  sceneId: string,
  objectId: string,
  type: Component['type'],
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  objects: scene.objects.map((object) => object.id === objectId ? {
    ...object,
    components: object.components.filter((component) => component.type !== type),
  } : object),
}));

const defaultEventAction = (project: GameProject): Action => {
  const overlay = project.scenes.find((scene) => scene.type === 'DIALOGUE' && scene.presentation === 'OVERLAY');
  return overlay === undefined
    ? { type: 'COMPLETE_GAME' }
    : { type: 'SHOW_DIALOGUE', sceneId: overlay.id };
};

export const addObjectEvent = (
  project: GameProject,
  sceneId: string,
  objectId: string,
): { readonly project: GameProject; readonly eventId: string } => {
  const eventId = nextStableId(project, 'event');
  const event: GameEvent = {
    id: eventId,
    trigger: { type: 'ON_INTERACT', targetId: objectId },
    conditions: [],
    actions: [defaultEventAction(project)],
  };
  return {
    project: replaceTopDownScene(project, sceneId, (scene) => ({
      ...scene,
      events: [...scene.events, event],
    })),
    eventId,
  };
};

export type BehaviorRecipe = 'TALK' | 'PICKUP' | 'LOCKED_DOOR' | 'GOAL' | 'ENTER_DIALOGUE';

export const applyBehaviorRecipe = (
  project: GameProject,
  sceneId: string,
  objectId: string,
  recipe: BehaviorRecipe,
): GameProject => {
  const overlay = project.scenes.find((scene) => scene.type === 'DIALOGUE' && scene.presentation === 'OVERLAY');
  const item = project.items[0];
  const targetScene = project.scenes.find((scene) => scene.id !== sceneId && (scene.type !== 'DIALOGUE' || scene.presentation === 'FULL_SCREEN'));
  const eventId = nextStableId(project, 'event');
  let event: GameEvent;
  if (recipe === 'TALK' || recipe === 'ENTER_DIALOGUE') {
    if (overlay === undefined) throw new Error('먼저 “+ 대화”로 대화 장면을 하나 만들어 주세요.');
    event = {
      id: eventId,
      trigger: recipe === 'TALK' ? { type: 'ON_INTERACT', targetId: objectId } : { type: 'ON_ENTER', targetId: objectId },
      conditions: [],
      actions: [{ type: 'SHOW_DIALOGUE', sceneId: overlay.id }],
    };
  } else if (recipe === 'PICKUP') {
    if (item === undefined) throw new Error('먼저 데이터 탭에서 아이템을 하나 만들어 주세요.');
    event = {
      id: eventId,
      trigger: { type: 'ON_ENTER', targetId: objectId },
      conditions: [],
      actions: [{ type: 'GIVE_ITEM', itemId: item.id }, { type: 'HIDE_OBJECT', objectId }],
    };
  } else if (recipe === 'LOCKED_DOOR') {
    if (item === undefined || targetScene === undefined) throw new Error('잠긴 문에는 아이템과 이동할 다른 장면이 필요합니다.');
    event = {
      id: eventId,
      trigger: { type: 'ON_INTERACT', targetId: objectId },
      conditions: [{ type: 'HAS_ITEM', itemId: item.id }],
      actions: [{ type: 'GO_TO_SCENE', sceneId: targetScene.id }],
    };
  } else {
    event = {
      id: eventId,
      trigger: { type: 'ON_ENTER', targetId: objectId },
      conditions: [],
      actions: [{ type: 'COMPLETE_GAME' }],
    };
  }
  return replaceTopDownScene(project, sceneId, (scene) => ({
    ...scene,
    objects: scene.objects.map((object) => {
      if (object.id !== objectId) return object;
      const components = [...object.components];
      if ((recipe === 'TALK' || recipe === 'LOCKED_DOOR') && !components.some((component) => component.type === 'INTERACTABLE')) {
        components.push({ type: 'INTERACTABLE', prompt: recipe === 'TALK' ? '대화하기' : '열기' });
      }
      if (recipe === 'PICKUP' && item !== undefined && !components.some((component) => component.type === 'PICKUP')) {
        components.push({ type: 'PICKUP', itemId: item.id });
      }
      return { ...object, components };
    }),
    events: [...scene.events, event],
  }));
};

export const removeEvent = (
  project: GameProject,
  sceneId: string,
  eventId: string,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  events: scene.events.filter((event) => event.id !== eventId),
}));

export const replaceEventTrigger = (
  project: GameProject,
  sceneId: string,
  eventId: string,
  trigger: Trigger,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  events: scene.events.map((event) => event.id === eventId ? { ...event, trigger } : event),
}));

export const createCondition = (
  project: GameProject,
  type: Condition['type'],
): Condition | null => {
  if (type === 'HAS_ITEM') {
    const item = project.items[0];
    return item === undefined ? null : { type, itemId: item.id };
  }
  const variable = project.variables[0];
  return variable === undefined ? null : {
    type,
    variableId: variable.id,
    value: variable.initialValue,
  };
};

export const appendEventCondition = (
  project: GameProject,
  sceneId: string,
  eventId: string,
  type: Condition['type'],
): GameProject => {
  const condition = createCondition(project, type);
  if (condition === null) throw new Error(`${type} 조건에 연결할 데이터가 없습니다.`);
  return replaceTopDownScene(project, sceneId, (scene) => ({
    ...scene,
    events: scene.events.map((event) => event.id === eventId ? {
      ...event,
      conditions: [...event.conditions, condition],
    } : event),
  }));
};

export const replaceEventCondition = (
  project: GameProject,
  sceneId: string,
  eventId: string,
  conditionIndex: number,
  condition: Condition,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  events: scene.events.map((event) => event.id === eventId ? {
    ...event,
    conditions: event.conditions.map((candidate, index) => index === conditionIndex ? condition : candidate),
  } : event),
}));

export const removeEventCondition = (
  project: GameProject,
  sceneId: string,
  eventId: string,
  conditionIndex: number,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  events: scene.events.map((event) => event.id === eventId ? {
    ...event,
    conditions: event.conditions.filter((_, index) => index !== conditionIndex),
  } : event),
}));

const firstObjectId = (project: GameProject): string | undefined => project.scenes
  .flatMap((scene) => scene.type !== 'DIALOGUE' ? scene.objects : [])
  .at(0)?.id;

export const createAction = (project: GameProject, type: Action['type']): Action | null => {
  if (type === 'COMPLETE_GAME' || type === 'CLOSE_DIALOGUE') return { type };
  if (type === 'SHOW_DIALOGUE') {
    const scene = project.scenes.find((candidate) => candidate.type === 'DIALOGUE' && candidate.presentation === 'OVERLAY');
    return scene === undefined ? null : { type, sceneId: scene.id };
  }
  if (type === 'GO_TO_SCENE') {
    const scene = project.scenes.find((candidate) => candidate.type !== 'DIALOGUE' || candidate.presentation === 'FULL_SCREEN');
    return scene === undefined ? null : { type, sceneId: scene.id };
  }
  if (type === 'SET_VARIABLE') {
    const variable = project.variables[0];
    return variable === undefined ? null : { type, variableId: variable.id, value: variable.initialValue };
  }
  if (type === 'GIVE_ITEM' || type === 'REMOVE_ITEM') {
    const item = project.items[0];
    return item === undefined ? null : { type, itemId: item.id };
  }
  const objectId = firstObjectId(project);
  return objectId === undefined ? null : { type, objectId };
};

export const appendEventAction = (
  project: GameProject,
  sceneId: string,
  eventId: string,
  type: Action['type'],
): GameProject => {
  const action = createAction(project, type);
  if (action === null) throw new Error(`${type} Action에 연결할 데이터가 없습니다.`);
  return replaceTopDownScene(project, sceneId, (scene) => ({
    ...scene,
    events: scene.events.map((event) => {
      if (event.id !== eventId) return event;
      const terminalIndex = event.actions.findIndex((candidate) => terminalActionTypes.has(candidate.type));
      const actions = [...event.actions];
      if (terminalIndex === -1) actions.push(action);
      else actions.splice(terminalIndex, 0, action);
      return { ...event, actions };
    }),
  }));
};

export const replaceEventAction = (
  project: GameProject,
  sceneId: string,
  eventId: string,
  actionIndex: number,
  action: Action,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  events: scene.events.map((event) => event.id === eventId ? {
    ...event,
    actions: event.actions.map((candidate, index) => index === actionIndex ? action : candidate),
  } : event),
}));

export const removeEventAction = (
  project: GameProject,
  sceneId: string,
  eventId: string,
  actionIndex: number,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  events: scene.events.map((event) => {
    if (event.id !== eventId || event.actions.length === 1) return event;
    return { ...event, actions: event.actions.filter((_, index) => index !== actionIndex) };
  }),
}));

export const updateDialogueNode = (
  project: GameProject,
  sceneId: string,
  nodeId: string,
  updater: (node: DialogueNode) => DialogueNode,
): GameProject => replaceDialogueScene(project, sceneId, (scene) => ({
  ...scene,
  nodes: scene.nodes.map((node) => node.id === nodeId ? updater(node) : node),
}));

export const addDialogueNode = (
  project: GameProject,
  sceneId: string,
): { readonly project: GameProject; readonly nodeId: string } => {
  const scene = project.scenes.find((candidate) => candidate.id === sceneId);
  if (scene?.type !== 'DIALOGUE') throw new Error(`${sceneId} is not a DIALOGUE scene`);
  const nodeId = nextStableId(project, 'node');
  const node: DialogueNode = { id: nodeId, speaker: '인물', text: '대사를 입력하세요.', choices: [] };
  return {
    project: replaceDialogueScene(project, sceneId, (current) => ({
      ...current,
      nodes: [...current.nodes, node],
    })),
    nodeId,
  };
};

export const addDialogueChoice = (
  project: GameProject,
  sceneId: string,
  nodeId: string,
): GameProject => {
  const scene = project.scenes.find((candidate) => candidate.id === sceneId);
  if (scene?.type !== 'DIALOGUE') throw new Error(`${sceneId} is not a DIALOGUE scene`);
  const choice: DialogueChoice = {
    id: nextStableId(project, 'choice'),
    text: '선택지',
    actions: [scene.presentation === 'OVERLAY' ? { type: 'CLOSE_DIALOGUE' } : { type: 'COMPLETE_GAME' }],
  };
  return updateDialogueNode(project, sceneId, nodeId, (node) => ({
    ...node,
    choices: [...node.choices, choice],
  }));
};

export const updateDialogueChoice = (
  project: GameProject,
  sceneId: string,
  nodeId: string,
  choiceId: string,
  updater: (choice: DialogueChoice) => DialogueChoice,
): GameProject => updateDialogueNode(project, sceneId, nodeId, (node) => ({
  ...node,
  choices: node.choices.map((choice) => choice.id === choiceId ? updater(choice) : choice),
}));

export const removeDialogueChoice = (
  project: GameProject,
  sceneId: string,
  nodeId: string,
  choiceId: string,
): GameProject => updateDialogueNode(project, sceneId, nodeId, (node) => ({
  ...node,
  choices: node.choices.filter((choice) => choice.id !== choiceId),
}));

export const addBooleanVariable = (project: GameProject): GameProject => {
  const id = nextStableId(project, 'variable');
  return validated({
    ...project,
    variables: [...project.variables, { id, type: 'BOOLEAN', initialValue: false }],
  });
};

export const replaceVariableDefinition = (
  project: GameProject,
  variableId: string,
  initialValue: boolean | number | string,
): GameProject => validated({
  ...project,
  variables: project.variables.map((variable) => variable.id === variableId
    ? { ...variable, initialValue }
    : variable),
});

export const addItemDefinition = (project: GameProject): GameProject => {
  const id = nextStableId(project, 'item');
  return validated({ ...project, items: [...project.items, { id, name: `새 아이템 ${project.items.length + 1}` }] });
};

export const renameItemDefinition = (
  project: GameProject,
  itemId: string,
  name: string,
): GameProject => validated({
  ...project,
  items: project.items.map((item) => item.id === itemId ? { ...item, name: name.trim() } : item),
});

export const addAssetReference = (
  project: GameProject,
  asset: AssetReference,
): GameProject => validated({ ...project, assets: [...project.assets, asset] });

export const addTileLayer = (
  project: GameProject,
  sceneId: string,
): { readonly project: GameProject; readonly layerId: string } => {
  const scene = project.scenes.find((candidate) => candidate.id === sceneId);
  if (scene?.type === 'DIALOGUE' || scene === undefined) throw new Error(`${sceneId} is not a world scene`);
  const preferredSource = scene.type === 'PLATFORMER'
    ? 'builtin://tilesets/platformer-starter'
    : 'builtin://tilesets/library';
  const tileset = project.assets.find((asset) => asset.kind === 'TILESET' && asset.source === preferredSource)
    ?? project.assets.find((asset) => asset.kind === 'TILESET');
  if (tileset === undefined) throw new Error('Tile Layer를 만들려면 TILESET 자산이 필요합니다.');
  let suffix = 1;
  while (scene.tileLayers.some((layer) => layer.id === `layer${suffix}`)) suffix += 1;
  const layerId = `layer${suffix}`;
  return {
    layerId,
    project: replaceTopDownScene(project, sceneId, (current) => ({
      ...current,
      tileLayers: [...current.tileLayers, {
        id: layerId,
        name: `타일 레이어 ${current.tileLayers.length + 1}`,
        tilesetAssetId: tileset.id,
        data: Array.from({ length: current.width * current.height }, () => -1),
      }],
    })),
  };
};

export const paintTiles = (
  project: GameProject,
  sceneId: string,
  layerId: string,
  cells: readonly { readonly x: number; readonly y: number }[],
  tileIndex: number,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => {
  const dataIndexes = new Set(cells.map(({ x, y }) => {
    const gridX = Math.max(0, Math.min(scene.width - 1, Math.round(x)));
    const gridY = Math.max(0, Math.min(scene.height - 1, Math.round(y)));
    return gridY * scene.width + gridX;
  }));
  return {
    ...scene,
    tileLayers: scene.tileLayers.map((layer) => layer.id === layerId ? {
      ...layer,
      data: layer.data.map((tile, index) => dataIndexes.has(index) ? Math.max(-1, Math.round(tileIndex)) : tile),
    } : layer),
  };
});

export const paintTile = (
  project: GameProject,
  sceneId: string,
  layerId: string,
  x: number,
  y: number,
  tileIndex: number,
): GameProject => paintTiles(project, sceneId, layerId, [{ x, y }], tileIndex);

export const fillTileLayer = (
  project: GameProject,
  sceneId: string,
  layerId: string,
  tileIndex: number,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  tileLayers: scene.tileLayers.map((layer) => layer.id === layerId ? {
    ...layer,
    data: layer.data.map(() => Math.max(-1, Math.round(tileIndex))),
  } : layer),
}));

export const floodFillTiles = (
  project: GameProject,
  sceneId: string,
  layerId: string,
  x: number,
  y: number,
  tileIndex: number,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => {
  const layer = scene.tileLayers.find((candidate) => candidate.id === layerId);
  if (layer === undefined) throw new Error(`${layerId} Tile Layer를 찾을 수 없습니다.`);
  const startX = Math.max(0, Math.min(scene.width - 1, Math.round(x)));
  const startY = Math.max(0, Math.min(scene.height - 1, Math.round(y)));
  const startIndex = startY * scene.width + startX;
  const sourceTile = layer.data[startIndex] ?? -1;
  const replacementTile = Math.max(-1, Math.round(tileIndex));
  if (sourceTile === replacementTile) return scene;

  const data = [...layer.data];
  const queue = [startIndex];
  const visited = new Set<number>([startIndex]);
  for (let cursor = 0; cursor < queue.length; cursor += 1) {
    const index = queue[cursor];
    if (index === undefined || data[index] !== sourceTile) continue;
    data[index] = replacementTile;
    const cellX = index % scene.width;
    const cellY = Math.floor(index / scene.width);
    const neighbours = [
      cellX > 0 ? index - 1 : -1,
      cellX < scene.width - 1 ? index + 1 : -1,
      cellY > 0 ? index - scene.width : -1,
      cellY < scene.height - 1 ? index + scene.width : -1,
    ];
    neighbours.forEach((neighbour) => {
      if (neighbour >= 0 && !visited.has(neighbour) && data[neighbour] === sourceTile) {
        visited.add(neighbour);
        queue.push(neighbour);
      }
    });
  }
  return {
    ...scene,
    tileLayers: scene.tileLayers.map((candidate) => candidate.id === layerId ? { ...candidate, data } : candidate),
  };
});

export const removeTileLayer = (
  project: GameProject,
  sceneId: string,
  layerId: string,
): GameProject => replaceTopDownScene(project, sceneId, (scene) => ({
  ...scene,
  tileLayers: scene.tileLayers.filter((layer) => layer.id !== layerId),
}));
