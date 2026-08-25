export type Scalar = boolean | number | string;

export const GAME_PROJECT_LIMITS = {
  maxJsonBytes: 2_000_000,
  maxScenes: 50,
  maxObjectsPerScene: 500,
  maxEventsPerScene: 300,
  maxAssets: 300,
  maxTileCellsPerLayer: 10_000,
} as const;

export const estimateGameProjectJsonBytes = (project: GameProject): number => (
  new TextEncoder().encode(JSON.stringify(project)).byteLength
);

export type VariableType = 'BOOLEAN' | 'INTEGER' | 'STRING';

export interface VariableDefinition {
  readonly id: string;
  readonly type: VariableType;
  readonly initialValue: Scalar;
}

export interface ItemDefinition {
  readonly id: string;
  readonly name: string;
  readonly assetId?: string;
}

export interface AssetReference {
  readonly id: string;
  readonly kind: 'IMAGE' | 'TILESET' | 'AUDIO';
  readonly source: string;
  readonly integrity?: string;
}

export type GameObjectiveType = 'SCORE_AT_LEAST' | 'DEFEAT_ENEMIES' | 'SURVIVE_SECONDS';

export interface GameObjective {
  readonly type: GameObjectiveType;
  readonly target: number;
}

export interface GameRules {
  readonly completion: {
    readonly mode: 'ALL' | 'ANY';
    readonly objectives: readonly GameObjective[];
  };
  readonly playerDefeat: 'RESPAWN' | 'END_GAME';
}

export const DEFAULT_GAME_RULES: GameRules = Object.freeze({
  completion: Object.freeze({ mode: 'ALL', objectives: Object.freeze([]) }),
  playerDefeat: 'RESPAWN',
});

export interface Position2d {
  readonly x: number;
  readonly y: number;
}

export type Component =
  | { readonly type: 'SPRITE'; readonly assetId: string; readonly scale?: number; readonly zIndex?: number }
  | { readonly type: 'COLLIDER'; readonly solid: boolean }
  | { readonly type: 'INTERACTABLE'; readonly prompt: string }
  | { readonly type: 'PICKUP'; readonly itemId: string }
  | { readonly type: 'DAMAGE'; readonly amount: number }
  | { readonly type: 'HEALTH'; readonly max: number }
  | { readonly type: 'SCORE_VALUE'; readonly value: number }
  | { readonly type: 'CHECKPOINT' }
  | { readonly type: 'AUTO_MOVE'; readonly axis: 'HORIZONTAL' | 'VERTICAL'; readonly speed: number; readonly range: number }
  | { readonly type: 'SHOOTER'; readonly projectileAssetId: string; readonly damage: number; readonly cooldownMs: number }
  | { readonly type: 'SPAWNER'; readonly enemyAssetId: string; readonly intervalMs: number; readonly maxAlive: number };

export interface GameObject {
  readonly id: string;
  readonly preset:
    | 'PLAYER_SPAWN'
    | 'WALL'
    | 'NPC'
    | 'INTERACTABLE'
    | 'ITEM'
    | 'DOOR'
    | 'GOAL'
    | 'PLATFORM'
    | 'HAZARD'
    | 'ENEMY'
    | 'TURRET'
    | 'CHECKPOINT'
    | 'SPAWNER'
    | 'DECORATION';
  readonly position: Position2d;
  readonly visible: boolean;
  readonly components: readonly Component[];
}

export type Trigger =
  | { readonly type: 'ON_SCENE_START' }
  | { readonly type: 'ON_INTERACT' | 'ON_ENTER'; readonly targetId: string };

export type Condition =
  | { readonly type: 'VARIABLE_EQUALS'; readonly variableId: string; readonly value: Scalar }
  | { readonly type: 'HAS_ITEM'; readonly itemId: string };

export type Action =
  | { readonly type: 'SHOW_DIALOGUE'; readonly sceneId: string }
  | { readonly type: 'SET_VARIABLE'; readonly variableId: string; readonly value: Scalar }
  | { readonly type: 'GIVE_ITEM' | 'REMOVE_ITEM'; readonly itemId: string }
  | { readonly type: 'SHOW_OBJECT' | 'HIDE_OBJECT'; readonly objectId: string }
  | { readonly type: 'GO_TO_SCENE'; readonly sceneId: string }
  | { readonly type: 'COMPLETE_GAME' }
  | { readonly type: 'CLOSE_DIALOGUE' };

export interface GameEvent {
  readonly id: string;
  readonly trigger: Trigger;
  readonly conditions: readonly Condition[];
  readonly actions: readonly Action[];
}

export interface TileLayer {
  readonly id: string;
  readonly name: string;
  readonly tilesetAssetId: string;
  readonly data: readonly number[];
}

export interface TopDownScene {
  readonly id: string;
  readonly type: 'TOP_DOWN';
  readonly name: string;
  readonly width: number;
  readonly height: number;
  readonly backgroundAssetId?: string;
  readonly tileLayers: readonly TileLayer[];
  readonly objects: readonly GameObject[];
  readonly events: readonly GameEvent[];
}

export interface PlatformerScene {
  readonly id: string;
  readonly type: 'PLATFORMER';
  readonly name: string;
  readonly width: number;
  readonly height: number;
  readonly backgroundAssetId?: string;
  readonly gravity: number;
  readonly tileLayers: readonly TileLayer[];
  readonly objects: readonly GameObject[];
  readonly events: readonly GameEvent[];
}

export type WorldScene = TopDownScene | PlatformerScene;

export interface DialogueChoice {
  readonly id: string;
  readonly text: string;
  readonly conditions?: readonly Condition[];
  readonly nextNodeId?: string;
  readonly actions: readonly Action[];
}

export interface DialogueNode {
  readonly id: string;
  readonly speaker: string;
  readonly text: string;
  readonly portraitAssetId?: string;
  readonly choices: readonly DialogueChoice[];
}

export interface DialogueScene {
  readonly id: string;
  readonly type: 'DIALOGUE';
  readonly name: string;
  readonly presentation: 'OVERLAY' | 'FULL_SCREEN';
  readonly backgroundAssetId?: string;
  readonly startNodeId: string;
  readonly nodes: readonly DialogueNode[];
}

export type GameScene = WorldScene | DialogueScene;

export interface GameProject {
  readonly schemaVersion: '1.0.0' | '1.1.0';
  readonly gameId: number;
  readonly revision: number;
  readonly title: string;
  readonly startSceneId: string;
  readonly variables: readonly VariableDefinition[];
  readonly items: readonly ItemDefinition[];
  readonly assets: readonly AssetReference[];
  readonly scenes: readonly GameScene[];
  readonly rules?: GameRules;
}

export class GameProjectContractError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'GameProjectContractError';
    this.code = code;
  }
}

type UnknownRecord = Record<string, unknown>;

const stableIdPattern = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
const terminalActions = new Set<Action['type']>([
  'SHOW_DIALOGUE',
  'CLOSE_DIALOGUE',
  'GO_TO_SCENE',
  'COMPLETE_GAME',
]);

const fail = (code: string, message: string): never => {
  throw new GameProjectContractError(code, message);
};

const expect = (condition: boolean, code: string, message: string): void => {
  if (!condition) fail(code, message);
};

const isRecord = (value: unknown): value is UnknownRecord => (
  typeof value === 'object' && value !== null && !Array.isArray(value)
);
const recordAt = (
  value: unknown,
  path: string,
  required: readonly string[],
  allowed: readonly string[] = required,
): UnknownRecord => {
  expect(isRecord(value), 'GAME_PROJECT_INVALID', `${path} must be an object`);
  const record = value as UnknownRecord;
  for (const key of required) {
    expect(Object.hasOwn(record, key), 'GAME_PROJECT_INVALID', `${path}.${key} is required`);
  }
  for (const key of Object.keys(record)) {
    expect(allowed.includes(key), 'GAME_PROJECT_INVALID', `${path}.${key} is not allowed`);
  }
  return record;
};

const arrayAt = (value: unknown, path: string, min: number, max: number): readonly unknown[] => {
  expect(Array.isArray(value), 'GAME_PROJECT_INVALID', `${path} must be an array`);
  const array = value as readonly unknown[];
  expect(array.length >= min && array.length <= max, 'GAME_PROJECT_INVALID', `${path} size must be ${min}..${max}`);
  return array;
};

const stringAt = (value: unknown, path: string, min: number, max: number): string => {
  expect(typeof value === 'string', 'GAME_PROJECT_INVALID', `${path} must be a string`);
  const string = value as string;
  expect(string.length >= min && string.length <= max, 'GAME_PROJECT_INVALID', `${path} length must be ${min}..${max}`);
  return string;
};

const integerAt = (value: unknown, path: string, min: number, max = Number.MAX_SAFE_INTEGER): number => {
  expect(Number.isInteger(value), 'GAME_PROJECT_INVALID', `${path} must be an integer`);
  const integer = value as number;
  expect(integer >= min && integer <= max, 'GAME_PROJECT_INVALID', `${path} must be ${min}..${max}`);
  return integer;
};

const stableIdAt = (value: unknown, path: string): string => {
  const id = stringAt(value, path, 1, 64);
  expect(stableIdPattern.test(id), 'GAME_PROJECT_INVALID', `${path} is not a stable id`);
  return id;
};

const scalarAt = (value: unknown, path: string): Scalar => {
  const valid = typeof value === 'boolean'
    || Number.isInteger(value)
    || (typeof value === 'string' && value.length <= 500);
  expect(valid, 'GAME_PROJECT_INVALID', `${path} must be a boolean, integer, or string`);
  return value as Scalar;
};

const enumAt = <T extends string>(
  value: unknown,
  path: string,
  allowed: readonly T[],
): T => {
  expect(typeof value === 'string' && allowed.includes(value as T), 'GAME_PROJECT_INVALID', `${path} is invalid`);
  return value as T;
};

const validateVariableShape = (value: unknown, path: string): void => {
  const record = recordAt(value, path, ['id', 'type', 'initialValue']);
  stableIdAt(record.id, `${path}.id`);
  enumAt(record.type, `${path}.type`, ['BOOLEAN', 'INTEGER', 'STRING']);
  scalarAt(record.initialValue, `${path}.initialValue`);
};

const validateItemShape = (value: unknown, path: string): void => {
  const record = recordAt(value, path, ['id', 'name'], ['id', 'name', 'assetId']);
  stableIdAt(record.id, `${path}.id`);
  stringAt(record.name, `${path}.name`, 1, 50);
  if (record.assetId !== undefined) stableIdAt(record.assetId, `${path}.assetId`);
};

const validateAssetShape = (value: unknown, path: string): void => {
  const record = recordAt(value, path, ['id', 'kind', 'source'], ['id', 'kind', 'source', 'integrity']);
  stableIdAt(record.id, `${path}.id`);
  enumAt(record.kind, `${path}.kind`, ['IMAGE', 'TILESET', 'AUDIO']);
  stringAt(record.source, `${path}.source`, 1, 500);
  if (record.integrity !== undefined) stringAt(record.integrity, `${path}.integrity`, 0, 128);
};

const validateGameRulesShape = (value: unknown, path: string): void => {
  const rules = recordAt(value, path, ['completion', 'playerDefeat']);
  enumAt(rules.playerDefeat, `${path}.playerDefeat`, ['RESPAWN', 'END_GAME']);
  const completion = recordAt(rules.completion, `${path}.completion`, ['mode', 'objectives']);
  enumAt(completion.mode, `${path}.completion.mode`, ['ALL', 'ANY']);
  const objectiveTypes = new Set<string>();
  arrayAt(completion.objectives, `${path}.completion.objectives`, 0, 5).forEach((value, index) => {
    const objectivePath = `${path}.completion.objectives[${index}]`;
    const objective = recordAt(value, objectivePath, ['type', 'target']);
    const type = enumAt(objective.type, `${objectivePath}.type`, ['SCORE_AT_LEAST', 'DEFEAT_ENEMIES', 'SURVIVE_SECONDS']);
    expect(!objectiveTypes.has(type), 'GAME_RULE_DUPLICATE_OBJECTIVE', `duplicate objective type: ${type}`);
    objectiveTypes.add(type);
    integerAt(
      objective.target,
      `${objectivePath}.target`,
      1,
      type === 'SURVIVE_SECONDS' ? 3600 : type === 'DEFEAT_ENEMIES' ? 10000 : 999999999,
    );
  });
};

const validateConditionShape = (value: unknown, path: string): void => {
  const base = recordAt(value, path, ['type'], ['type', 'variableId', 'value', 'itemId']);
  if (base.type === 'VARIABLE_EQUALS') {
    const record = recordAt(value, path, ['type', 'variableId', 'value']);
    stableIdAt(record.variableId, `${path}.variableId`);
    scalarAt(record.value, `${path}.value`);
    return;
  }
  if (base.type === 'HAS_ITEM') {
    const record = recordAt(value, path, ['type', 'itemId']);
    stableIdAt(record.itemId, `${path}.itemId`);
    return;
  }
  fail('GAME_PROJECT_INVALID', `${path}.type is invalid`);
};

const validateActionShape = (value: unknown, path: string): void => {
  const base = recordAt(value, path, ['type'], ['type', 'sceneId', 'variableId', 'value', 'itemId', 'objectId']);
  switch (base.type) {
    case 'SHOW_DIALOGUE':
    case 'GO_TO_SCENE': {
      const record = recordAt(value, path, ['type', 'sceneId']);
      stableIdAt(record.sceneId, `${path}.sceneId`);
      return;
    }
    case 'SET_VARIABLE': {
      const record = recordAt(value, path, ['type', 'variableId', 'value']);
      stableIdAt(record.variableId, `${path}.variableId`);
      scalarAt(record.value, `${path}.value`);
      return;
    }
    case 'GIVE_ITEM':
    case 'REMOVE_ITEM': {
      const record = recordAt(value, path, ['type', 'itemId']);
      stableIdAt(record.itemId, `${path}.itemId`);
      return;
    }
    case 'SHOW_OBJECT':
    case 'HIDE_OBJECT': {
      const record = recordAt(value, path, ['type', 'objectId']);
      stableIdAt(record.objectId, `${path}.objectId`);
      return;
    }
    case 'COMPLETE_GAME':
    case 'CLOSE_DIALOGUE':
      recordAt(value, path, ['type']);
      return;
    default:
      fail('GAME_PROJECT_INVALID', `${path}.type is invalid`);
  }
};

const validateTriggerShape = (value: unknown, path: string): void => {
  const base = recordAt(value, path, ['type'], ['type', 'targetId']);
  if (base.type === 'ON_SCENE_START') {
    recordAt(value, path, ['type']);
    return;
  }
  if (base.type === 'ON_INTERACT' || base.type === 'ON_ENTER') {
    const record = recordAt(value, path, ['type', 'targetId']);
    stableIdAt(record.targetId, `${path}.targetId`);
    return;
  }
  fail('GAME_PROJECT_INVALID', `${path}.type is invalid`);
};

const validateEventShape = (value: unknown, path: string): void => {
  const record = recordAt(value, path, ['id', 'trigger', 'conditions', 'actions']);
  stableIdAt(record.id, `${path}.id`);
  validateTriggerShape(record.trigger, `${path}.trigger`);
  arrayAt(record.conditions, `${path}.conditions`, 0, 10)
    .forEach((condition, index) => validateConditionShape(condition, `${path}.conditions[${index}]`));
  arrayAt(record.actions, `${path}.actions`, 1, 20)
    .forEach((action, index) => validateActionShape(action, `${path}.actions[${index}]`));
};

const validateComponentShape = (value: unknown, path: string): void => {
  const base = recordAt(value, path, ['type'], [
    'type', 'assetId', 'scale', 'zIndex', 'solid', 'prompt', 'itemId', 'amount', 'max', 'value',
    'axis', 'speed', 'range', 'projectileAssetId', 'damage', 'cooldownMs', 'enemyAssetId', 'intervalMs', 'maxAlive',
  ]);
  if (base.type === 'SPRITE') {
    const record = recordAt(value, path, ['type', 'assetId'], ['type', 'assetId', 'scale', 'zIndex']);
    stableIdAt(record.assetId, `${path}.assetId`);
    if (record.scale !== undefined) integerAt(record.scale, `${path}.scale`, 25, 400);
    if (record.zIndex !== undefined) integerAt(record.zIndex, `${path}.zIndex`, 0, 20);
    return;
  }
  if (base.type === 'COLLIDER') {
    const record = recordAt(value, path, ['type', 'solid']);
    expect(typeof record.solid === 'boolean', 'GAME_PROJECT_INVALID', `${path}.solid must be boolean`);
    return;
  }
  if (base.type === 'INTERACTABLE') {
    const record = recordAt(value, path, ['type', 'prompt']);
    stringAt(record.prompt, `${path}.prompt`, 1, 80);
    return;
  }
  if (base.type === 'PICKUP') {
    const record = recordAt(value, path, ['type', 'itemId']);
    stableIdAt(record.itemId, `${path}.itemId`);
    return;
  }
  if (base.type === 'DAMAGE') {
    const record = recordAt(value, path, ['type', 'amount']);
    integerAt(record.amount, `${path}.amount`, 1, 999);
    return;
  }
  if (base.type === 'HEALTH') {
    const record = recordAt(value, path, ['type', 'max']);
    integerAt(record.max, `${path}.max`, 1, 9999);
    return;
  }
  if (base.type === 'SCORE_VALUE') {
    const record = recordAt(value, path, ['type', 'value']);
    integerAt(record.value, `${path}.value`, -999999, 999999);
    return;
  }
  if (base.type === 'CHECKPOINT') {
    recordAt(value, path, ['type']);
    return;
  }
  if (base.type === 'AUTO_MOVE') {
    const record = recordAt(value, path, ['type', 'axis', 'speed', 'range']);
    enumAt(record.axis, `${path}.axis`, ['HORIZONTAL', 'VERTICAL']);
    integerAt(record.speed, `${path}.speed`, 1, 20);
    integerAt(record.range, `${path}.range`, 1, 100);
    return;
  }
  if (base.type === 'SHOOTER') {
    const record = recordAt(value, path, ['type', 'projectileAssetId', 'damage', 'cooldownMs']);
    stableIdAt(record.projectileAssetId, `${path}.projectileAssetId`);
    integerAt(record.damage, `${path}.damage`, 1, 999);
    integerAt(record.cooldownMs, `${path}.cooldownMs`, 100, 10000);
    return;
  }
  if (base.type === 'SPAWNER') {
    const record = recordAt(value, path, ['type', 'enemyAssetId', 'intervalMs', 'maxAlive']);
    stableIdAt(record.enemyAssetId, `${path}.enemyAssetId`);
    integerAt(record.intervalMs, `${path}.intervalMs`, 250, 60000);
    integerAt(record.maxAlive, `${path}.maxAlive`, 1, 100);
    return;
  }
  fail('GAME_PROJECT_INVALID', `${path}.type is invalid`);
};

const validateObjectShape = (value: unknown, path: string): void => {
  const record = recordAt(value, path, ['id', 'preset', 'position', 'visible', 'components']);
  stableIdAt(record.id, `${path}.id`);
  enumAt(record.preset, `${path}.preset`, [
    'PLAYER_SPAWN', 'WALL', 'NPC', 'INTERACTABLE', 'ITEM', 'DOOR', 'GOAL',
    'PLATFORM', 'HAZARD', 'ENEMY', 'TURRET', 'CHECKPOINT', 'SPAWNER', 'DECORATION',
  ]);
  const position = recordAt(record.position, `${path}.position`, ['x', 'y']);
  integerAt(position.x, `${path}.position.x`, 0);
  integerAt(position.y, `${path}.position.y`, 0);
  expect(typeof record.visible === 'boolean', 'GAME_PROJECT_INVALID', `${path}.visible must be boolean`);
  arrayAt(record.components, `${path}.components`, 0, 10)
    .forEach((component, index) => validateComponentShape(component, `${path}.components[${index}]`));
};

const validateTileLayerShape = (value: unknown, path: string): void => {
  const record = recordAt(value, path, ['id', 'name', 'tilesetAssetId', 'data']);
  stableIdAt(record.id, `${path}.id`);
  stringAt(record.name, `${path}.name`, 1, 50);
  stableIdAt(record.tilesetAssetId, `${path}.tilesetAssetId`);
  arrayAt(record.data, `${path}.data`, 0, GAME_PROJECT_LIMITS.maxTileCellsPerLayer)
    .forEach((tile, index) => integerAt(tile, `${path}.data[${index}]`, -1));
};

const validateDialogueChoiceShape = (value: unknown, path: string): void => {
  const record = recordAt(
    value,
    path,
    ['id', 'text', 'actions'],
    ['id', 'text', 'conditions', 'nextNodeId', 'actions'],
  );
  stableIdAt(record.id, `${path}.id`);
  stringAt(record.text, `${path}.text`, 1, 300);
  if (record.conditions !== undefined) {
    arrayAt(record.conditions, `${path}.conditions`, 0, 10)
      .forEach((condition, index) => validateConditionShape(condition, `${path}.conditions[${index}]`));
  }
  if (record.nextNodeId !== undefined) stableIdAt(record.nextNodeId, `${path}.nextNodeId`);
  arrayAt(record.actions, `${path}.actions`, 0, 20)
    .forEach((action, index) => validateActionShape(action, `${path}.actions[${index}]`));
};

const validateDialogueNodeShape = (value: unknown, path: string): void => {
  const record = recordAt(value, path, ['id', 'speaker', 'text', 'choices'], ['id', 'speaker', 'text', 'portraitAssetId', 'choices']);
  stableIdAt(record.id, `${path}.id`);
  stringAt(record.speaker, `${path}.speaker`, 0, 50);
  stringAt(record.text, `${path}.text`, 1, 1_000);
  if (record.portraitAssetId !== undefined) stableIdAt(record.portraitAssetId, `${path}.portraitAssetId`);
  arrayAt(record.choices, `${path}.choices`, 0, 6)
    .forEach((choice, index) => validateDialogueChoiceShape(choice, `${path}.choices[${index}]`));
};

const validateSceneShape = (value: unknown, path: string): void => {
  const base = recordAt(
    value,
    path,
    ['id', 'type', 'name'],
    ['id', 'type', 'name', 'width', 'height', 'backgroundAssetId', 'gravity', 'tileLayers', 'objects', 'events', 'presentation', 'startNodeId', 'nodes'],
  );
  stableIdAt(base.id, `${path}.id`);
  stringAt(base.name, `${path}.name`, 1, 50);

  if (base.type === 'TOP_DOWN') {
    const record = recordAt(value, path, ['id', 'type', 'name', 'width', 'height', 'tileLayers', 'objects', 'events'], ['id', 'type', 'name', 'width', 'height', 'backgroundAssetId', 'tileLayers', 'objects', 'events']);
    integerAt(record.width, `${path}.width`, 4, 100);
    integerAt(record.height, `${path}.height`, 4, 100);
    if (record.backgroundAssetId !== undefined) stableIdAt(record.backgroundAssetId, `${path}.backgroundAssetId`);
    arrayAt(record.tileLayers, `${path}.tileLayers`, 0, 10)
      .forEach((layer, index) => validateTileLayerShape(layer, `${path}.tileLayers[${index}]`));
    arrayAt(record.objects, `${path}.objects`, 0, 500)
      .forEach((object, index) => validateObjectShape(object, `${path}.objects[${index}]`));
    arrayAt(record.events, `${path}.events`, 0, 300)
      .forEach((event, index) => validateEventShape(event, `${path}.events[${index}]`));
    return;
  }
  if (base.type === 'PLATFORMER') {
    const record = recordAt(value, path, ['id', 'type', 'name', 'width', 'height', 'gravity', 'tileLayers', 'objects', 'events'], ['id', 'type', 'name', 'width', 'height', 'backgroundAssetId', 'gravity', 'tileLayers', 'objects', 'events']);
    integerAt(record.width, `${path}.width`, 8, 200);
    integerAt(record.height, `${path}.height`, 6, 100);
    integerAt(record.gravity, `${path}.gravity`, 1, 30);
    if (record.backgroundAssetId !== undefined) stableIdAt(record.backgroundAssetId, `${path}.backgroundAssetId`);
    arrayAt(record.tileLayers, `${path}.tileLayers`, 0, 10)
      .forEach((layer, index) => validateTileLayerShape(layer, `${path}.tileLayers[${index}]`));
    arrayAt(record.objects, `${path}.objects`, 0, 500)
      .forEach((object, index) => validateObjectShape(object, `${path}.objects[${index}]`));
    arrayAt(record.events, `${path}.events`, 0, 300)
      .forEach((event, index) => validateEventShape(event, `${path}.events[${index}]`));
    return;
  }

  if (base.type === 'DIALOGUE') {
    const record = recordAt(value, path, ['id', 'type', 'name', 'presentation', 'startNodeId', 'nodes'], ['id', 'type', 'name', 'presentation', 'backgroundAssetId', 'startNodeId', 'nodes']);
    enumAt(record.presentation, `${path}.presentation`, ['OVERLAY', 'FULL_SCREEN']);
    if (record.backgroundAssetId !== undefined) stableIdAt(record.backgroundAssetId, `${path}.backgroundAssetId`);
    stableIdAt(record.startNodeId, `${path}.startNodeId`);
    arrayAt(record.nodes, `${path}.nodes`, 1, 300)
      .forEach((node, index) => validateDialogueNodeShape(node, `${path}.nodes[${index}]`));
    return;
  }

  fail('GAME_PROJECT_INVALID', `${path}.type is invalid`);
};

const assertUniqueIds = <T extends { readonly id: string }>(
  values: readonly T[],
  namespace: string,
  duplicateCode: string,
): ReadonlyMap<string, T> => {
  const result = new Map<string, T>();
  for (const value of values) {
    expect(!result.has(value.id), duplicateCode, `duplicate ${namespace} id: ${value.id}`);
    result.set(value.id, value);
  }
  return result;
};

const scalarMatchesType = (value: Scalar, type: VariableType): boolean => {
  if (type === 'BOOLEAN') return typeof value === 'boolean';
  if (type === 'INTEGER') return Number.isInteger(value);
  return typeof value === 'string';
};

const validateSemantics = (project: GameProject): void => {
  const scenes = assertUniqueIds(project.scenes, 'scene', 'DUPLICATE_SCENE_ID');
  const variables = assertUniqueIds(project.variables, 'variable', 'DUPLICATE_VARIABLE_ID');
  const items = assertUniqueIds(project.items, 'item', 'DUPLICATE_ITEM_ID');
  const assets = assertUniqueIds(project.assets, 'asset', 'DUPLICATE_ASSET_ID');

  const startScene = scenes.get(project.startSceneId);
  expect(startScene !== undefined, 'START_SCENE_NOT_FOUND', `unknown startSceneId: ${project.startSceneId}`);
  expect(
    startScene?.type !== 'DIALOGUE' || startScene.presentation !== 'OVERLAY',
    'DIALOGUE_PRESENTATION_INVALID',
    'OVERLAY dialogue cannot be the start scene',
  );

  for (const variable of project.variables) {
    expect(
      scalarMatchesType(variable.initialValue, variable.type),
      'VARIABLE_INITIAL_VALUE_INVALID',
      `${variable.id} initialValue type mismatch`,
    );
  }

  for (const asset of project.assets) {
    expect(
      /^(builtin|asset):\/\//.test(asset.source),
      'ASSET_SOURCE_INVALID',
      `persisted asset source must use builtin:// or asset://: ${asset.id}`,
    );
  }

  for (const item of project.items) {
    if (item.assetId !== undefined) {
      expect(assets.has(item.assetId), 'ITEM_ASSET_NOT_FOUND', `unknown item asset: ${item.assetId}`);
    }
  }

  const allObjects = assertUniqueIds(
    project.scenes.flatMap((scene) => scene.type !== 'DIALOGUE' ? scene.objects : []),
    'object',
    'DUPLICATE_OBJECT_ID',
  );
  assertUniqueIds(
    project.scenes.flatMap((scene) => scene.type !== 'DIALOGUE' ? scene.events : []),
    'event',
    'DUPLICATE_EVENT_ID',
  );

  const validateConditionReferences = (condition: Condition): void => {
    if (condition.type === 'HAS_ITEM') {
      expect(items.has(condition.itemId), 'ITEM_REFERENCE_NOT_FOUND', `unknown item: ${condition.itemId}`);
      return;
    }
    const variable = variables.get(condition.variableId);
    expect(variable !== undefined, 'VARIABLE_REFERENCE_NOT_FOUND', `unknown variable: ${condition.variableId}`);
    expect(
      variable !== undefined && scalarMatchesType(condition.value, variable.type),
      'VARIABLE_VALUE_TYPE_INVALID',
      `${condition.variableId} comparison type mismatch`,
    );
  };

  const validateActionReferences = (
    action: Action,
    dialoguePresentation?: DialogueScene['presentation'],
  ): void => {
    if (action.type === 'SET_VARIABLE') {
      const variable = variables.get(action.variableId);
      expect(variable !== undefined, 'VARIABLE_REFERENCE_NOT_FOUND', `unknown variable: ${action.variableId}`);
      expect(
        variable !== undefined && scalarMatchesType(action.value, variable.type),
        'VARIABLE_VALUE_TYPE_INVALID',
        `${action.variableId} assignment type mismatch`,
      );
    }
    if (action.type === 'GIVE_ITEM' || action.type === 'REMOVE_ITEM') {
      expect(items.has(action.itemId), 'ITEM_REFERENCE_NOT_FOUND', `unknown item: ${action.itemId}`);
    }
    if (action.type === 'SHOW_OBJECT' || action.type === 'HIDE_OBJECT') {
      expect(allObjects.has(action.objectId), 'OBJECT_REFERENCE_NOT_FOUND', `unknown object: ${action.objectId}`);
    }
    if (action.type === 'GO_TO_SCENE') {
      const scene = scenes.get(action.sceneId);
      expect(scene !== undefined, 'SCENE_REFERENCE_NOT_FOUND', `unknown scene: ${action.sceneId}`);
      expect(
        scene?.type !== 'DIALOGUE' || scene.presentation !== 'OVERLAY',
        'DIALOGUE_PRESENTATION_INVALID',
        `GO_TO_SCENE cannot target OVERLAY dialogue: ${action.sceneId}`,
      );
    }
    if (action.type === 'SHOW_DIALOGUE') {
      const scene = scenes.get(action.sceneId);
      expect(scene?.type === 'DIALOGUE', 'DIALOGUE_TARGET_INVALID', `not a DIALOGUE scene: ${action.sceneId}`);
      expect(
        scene?.type === 'DIALOGUE' && scene.presentation === 'OVERLAY',
        'DIALOGUE_PRESENTATION_INVALID',
        `SHOW_DIALOGUE requires OVERLAY presentation: ${action.sceneId}`,
      );
    }
    if (action.type === 'CLOSE_DIALOGUE') {
      expect(
        dialoguePresentation === 'OVERLAY',
        'DIALOGUE_CLOSE_CONTEXT_INVALID',
        'CLOSE_DIALOGUE requires an OVERLAY choice',
      );
    }
  };

  const validateActions = (
    actions: readonly Action[],
    ownerId: string,
    dialoguePresentation?: DialogueScene['presentation'],
  ): void => {
    actions.forEach((action) => validateActionReferences(action, dialoguePresentation));
    const terminalIndex = actions.findIndex((action) => terminalActions.has(action.type));
    expect(
      terminalIndex === -1 || terminalIndex === actions.length - 1,
      'TERMINAL_ACTION_NOT_LAST',
      `${ownerId} has actions after a terminal action`,
    );
  };

  for (const scene of project.scenes) {
    if (scene.type !== 'DIALOGUE') {
      if (scene.backgroundAssetId !== undefined) {
        expect(
          assets.get(scene.backgroundAssetId)?.kind === 'IMAGE',
          'SCENE_BACKGROUND_ASSET_INVALID',
          `invalid map background asset: ${scene.backgroundAssetId}`,
        );
      }
      expect(
        scene.objects.filter((object) => object.preset === 'PLAYER_SPAWN').length === 1,
        'PLAYER_SPAWN_COUNT_INVALID',
        `${scene.id} must have exactly one PLAYER_SPAWN`,
      );
      const localObjectIds = new Set(scene.objects.map((object) => object.id));

      for (const layer of scene.tileLayers) {
        expect(
          assets.get(layer.tilesetAssetId)?.kind === 'TILESET',
          'TILESET_ASSET_INVALID',
          `invalid tileset: ${layer.tilesetAssetId}`,
        );
        expect(
          layer.data.length === scene.width * scene.height,
          'TILE_COUNT_INVALID',
          `${scene.id}/${layer.id} tile count mismatch`,
        );
      }

      for (const object of scene.objects) {
        expect(
          object.position.x < scene.width && object.position.y < scene.height,
          'OBJECT_POSITION_INVALID',
          `${object.id} position is outside ${scene.id}`,
        );
        const componentTypes = object.components.map((component) => component.type);
        expect(
          new Set(componentTypes).size === componentTypes.length,
          'DUPLICATE_COMPONENT_TYPE',
          `${object.id} has duplicate component types`,
        );
        for (const component of object.components) {
          if (component.type === 'SPRITE') {
            expect(
              assets.get(component.assetId)?.kind === 'IMAGE',
              'SPRITE_ASSET_INVALID',
              `invalid sprite asset: ${component.assetId}`,
            );
          }
          if (component.type === 'PICKUP') {
            expect(items.has(component.itemId), 'PICKUP_ITEM_NOT_FOUND', `unknown pickup item: ${component.itemId}`);
          }
          if (component.type === 'SHOOTER') {
            expect(assets.get(component.projectileAssetId)?.kind === 'IMAGE', 'PROJECTILE_ASSET_INVALID', `invalid projectile asset: ${component.projectileAssetId}`);
          }
          if (component.type === 'SPAWNER') {
            expect(assets.get(component.enemyAssetId)?.kind === 'IMAGE', 'SPAWNER_ASSET_INVALID', `invalid enemy asset: ${component.enemyAssetId}`);
          }
        }
      }

      for (const event of scene.events) {
        if (event.trigger.type !== 'ON_SCENE_START') {
          expect(
            localObjectIds.has(event.trigger.targetId),
            'TRIGGER_TARGET_NOT_FOUND',
            `unknown trigger target: ${event.trigger.targetId}`,
          );
        }
        event.conditions.forEach(validateConditionReferences);
        validateActions(event.actions, event.id);
      }
      continue;
    }

    const nodes = assertUniqueIds(scene.nodes, `dialogue node in ${scene.id}`, 'DUPLICATE_DIALOGUE_NODE_ID');
    if (scene.backgroundAssetId !== undefined) {
      expect(
        assets.get(scene.backgroundAssetId)?.kind === 'IMAGE',
        'DIALOGUE_BACKGROUND_ASSET_INVALID',
        `invalid dialogue background asset: ${scene.backgroundAssetId}`,
      );
    }
    expect(nodes.has(scene.startNodeId), 'DIALOGUE_START_NODE_NOT_FOUND', `${scene.id} startNodeId does not exist`);
    for (const node of scene.nodes) {
      if (node.portraitAssetId !== undefined) {
        expect(
          assets.get(node.portraitAssetId)?.kind === 'IMAGE',
          'DIALOGUE_PORTRAIT_ASSET_INVALID',
          `invalid dialogue portrait asset: ${node.portraitAssetId}`,
        );
      }
      assertUniqueIds(node.choices, `choice in ${scene.id}/${node.id}`, 'DUPLICATE_DIALOGUE_CHOICE_ID');
      for (const choice of node.choices) {
        choice.conditions?.forEach(validateConditionReferences);
        if (choice.nextNodeId !== undefined) {
          expect(
            nodes.has(choice.nextNodeId),
            'NEXT_DIALOGUE_NODE_NOT_FOUND',
            `${scene.id} unknown nextNodeId: ${choice.nextNodeId}`,
          );
          expect(
            !choice.actions.some((action) => terminalActions.has(action.type)),
            'DIALOGUE_NEXT_WITH_TERMINAL_ACTION',
            `${choice.id} cannot combine nextNodeId with a terminal action`,
          );
        }
        validateActions(choice.actions, choice.id, scene.presentation);
      }
    }
  }
};

export const parseGameProject = (input: unknown): GameProject => {
  let jsonBytes = Number.POSITIVE_INFINITY;
  try {
    jsonBytes = new TextEncoder().encode(JSON.stringify(input)).byteLength;
  } catch {
    fail('GAME_PROJECT_INVALID', 'project must be JSON serializable');
  }
  expect(
    jsonBytes <= GAME_PROJECT_LIMITS.maxJsonBytes,
    'GAME_PROJECT_SIZE_EXCEEDED',
    `project JSON exceeds ${GAME_PROJECT_LIMITS.maxJsonBytes} bytes`,
  );
  const project = recordAt(
    input,
    'project',
    ['schemaVersion', 'gameId', 'revision', 'title', 'startSceneId', 'variables', 'items', 'assets', 'scenes'],
    ['schemaVersion', 'gameId', 'revision', 'title', 'startSceneId', 'variables', 'items', 'assets', 'scenes', 'rules'],
  );
  expect(
    project.schemaVersion === '1.0.0' || project.schemaVersion === '1.1.0',
    'GAME_SCHEMA_UNSUPPORTED',
    `unsupported schemaVersion: ${String(project.schemaVersion)}`,
  );
  if (project.schemaVersion === '1.0.0') {
    expect(project.rules === undefined, 'GAME_PROJECT_INVALID', 'project.rules requires schemaVersion 1.1.0');
  } else {
    expect(project.rules !== undefined, 'GAME_PROJECT_INVALID', 'project.rules is required for schemaVersion 1.1.0');
    validateGameRulesShape(project.rules, 'project.rules');
  }
  integerAt(project.gameId, 'project.gameId', 1);
  integerAt(project.revision, 'project.revision', 0);
  stringAt(project.title, 'project.title', 1, 100);
  stableIdAt(project.startSceneId, 'project.startSceneId');
  arrayAt(project.variables, 'project.variables', 0, 100)
    .forEach((variable, index) => validateVariableShape(variable, `project.variables[${index}]`));
  arrayAt(project.items, 'project.items', 0, 100)
    .forEach((item, index) => validateItemShape(item, `project.items[${index}]`));
  arrayAt(project.assets, 'project.assets', 0, 300)
    .forEach((asset, index) => validateAssetShape(asset, `project.assets[${index}]`));
  arrayAt(project.scenes, 'project.scenes', 1, 50)
    .forEach((scene, index) => validateSceneShape(scene, `project.scenes[${index}]`));

  const result = input as GameProject;
  validateSemantics(result);
  return result;
};

export const isGameProject = (input: unknown): input is GameProject => {
  try {
    parseGameProject(input);
    return true;
  } catch (error) {
    if (error instanceof GameProjectContractError) return false;
    throw error;
  }
};

export const findScene = (project: GameProject, sceneId: string): GameScene | undefined => (
  project.scenes.find((scene) => scene.id === sceneId)
);
