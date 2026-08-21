import type { GameProject } from '../../contracts/gameProject.ts';

export const minimalGameProject: GameProject = {
  schemaVersion: '1.0.0',
  gameId: 123,
  revision: 0,
  title: '열쇠와 문',
  startSceneId: 'room',
  variables: [
    { id: 'doorOpened', type: 'BOOLEAN', initialValue: false },
  ],
  items: [
    { id: 'key', name: '작은 열쇠', assetId: 'keyImage' },
  ],
  assets: [
    { id: 'basicTiles', kind: 'TILESET', source: 'builtin://tilesets/basic' },
    { id: 'playerImage', kind: 'IMAGE', source: 'builtin://sprites/player' },
    { id: 'keyImage', kind: 'IMAGE', source: 'builtin://sprites/key' },
    { id: 'doorImage', kind: 'IMAGE', source: 'builtin://sprites/door' },
  ],
  scenes: [
    {
      id: 'room',
      type: 'TOP_DOWN',
      name: '잠긴 방',
      width: 4,
      height: 4,
      tileLayers: [
        {
          id: 'floor',
          name: '바닥',
          tilesetAssetId: 'basicTiles',
          data: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
        },
      ],
      objects: [
        {
          id: 'playerSpawn',
          preset: 'PLAYER_SPAWN',
          position: { x: 1, y: 1 },
          visible: true,
          components: [
            { type: 'SPRITE', assetId: 'playerImage' },
            { type: 'COLLIDER', solid: true },
          ],
        },
        {
          id: 'roomKey',
          preset: 'ITEM',
          position: { x: 2, y: 1 },
          visible: true,
          components: [
            { type: 'SPRITE', assetId: 'keyImage' },
            { type: 'PICKUP', itemId: 'key' },
          ],
        },
        {
          id: 'exitDoor',
          preset: 'DOOR',
          position: { x: 3, y: 2 },
          visible: true,
          components: [
            { type: 'SPRITE', assetId: 'doorImage' },
            { type: 'COLLIDER', solid: true },
            { type: 'INTERACTABLE', prompt: '문 열기' },
          ],
        },
      ],
      events: [
        {
          id: 'takeKey',
          trigger: { type: 'ON_ENTER', targetId: 'roomKey' },
          conditions: [],
          actions: [
            { type: 'GIVE_ITEM', itemId: 'key' },
            { type: 'HIDE_OBJECT', objectId: 'roomKey' },
          ],
        },
        {
          id: 'openDoor',
          trigger: { type: 'ON_INTERACT', targetId: 'exitDoor' },
          conditions: [
            { type: 'HAS_ITEM', itemId: 'key' },
            { type: 'VARIABLE_EQUALS', variableId: 'doorOpened', value: false },
          ],
          actions: [
            { type: 'SET_VARIABLE', variableId: 'doorOpened', value: true },
            { type: 'SHOW_DIALOGUE', sceneId: 'doorHint' },
          ],
        },
        {
          id: 'leaveRoom',
          trigger: { type: 'ON_INTERACT', targetId: 'exitDoor' },
          conditions: [
            { type: 'VARIABLE_EQUALS', variableId: 'doorOpened', value: true },
          ],
          actions: [
            { type: 'GO_TO_SCENE', sceneId: 'ending' },
          ],
        },
      ],
    },
    {
      id: 'doorHint',
      type: 'DIALOGUE',
      name: '문 열림 안내',
      presentation: 'OVERLAY',
      startNodeId: 'opened',
      nodes: [
        {
          id: 'opened',
          speaker: '안내',
          text: '열쇠가 맞았습니다. 문이 열렸습니다.',
          choices: [
            {
              id: 'continue',
              text: '계속하기',
              actions: [{ type: 'CLOSE_DIALOGUE' }],
            },
          ],
        },
      ],
    },
    {
      id: 'ending',
      type: 'DIALOGUE',
      name: '탈출',
      presentation: 'FULL_SCREEN',
      startNodeId: 'success',
      nodes: [
        {
          id: 'success',
          speaker: '안내',
          text: '문이 열렸습니다. 방을 탈출했습니다!',
          choices: [
            {
              id: 'finish',
              text: '게임 끝내기',
              actions: [{ type: 'COMPLETE_GAME' }],
            },
          ],
        },
      ],
    },
  ],
};

export type DeepMutable<T> = T extends readonly (infer Item)[]
  ? DeepMutable<Item>[]
  : T extends object
    ? { -readonly [Key in keyof T]: DeepMutable<T[Key]> }
    : T;

export const cloneMinimalGameProject = (): DeepMutable<GameProject> => (
  structuredClone(minimalGameProject) as DeepMutable<GameProject>
);
