import type { GameProject } from '../../contracts/gameProject.ts';
import { BUILTIN_PROJECT_ASSETS } from '../assets/builtinAssetCatalog.ts';

const starterAssetSources = new Set([
  'builtin://tilesets/library', 'builtin://sprites/player', 'builtin://sprites/npc',
  'builtin://sprites/key', 'builtin://sprites/door', 'builtin://backgrounds/library-room-map',
  'builtin://backgrounds/visual-novel-library', 'builtin://portraits/librarian',
]);

export const createStarterProject = (gameId: number): GameProject => ({
  schemaVersion: '1.1.0',
  gameId,
  revision: 0,
  title: '비밀 도서관',
  rules: {
    completion: { mode: 'ALL', objectives: [] },
    playerDefeat: 'RESPAWN',
  },
  startSceneId: 'library',
  variables: [
    { id: 'doorOpened', type: 'BOOLEAN', initialValue: false },
  ],
  items: [
    { id: 'libraryKey', name: '황동 열쇠', assetId: 'keyImage' },
  ],
  assets: [
    { id: 'libraryTiles', kind: 'TILESET', source: 'builtin://tilesets/library' },
    { id: 'playerImage', kind: 'IMAGE', source: 'builtin://sprites/player' },
    { id: 'npcImage', kind: 'IMAGE', source: 'builtin://sprites/npc' },
    { id: 'keyImage', kind: 'IMAGE', source: 'builtin://sprites/key' },
    { id: 'doorImage', kind: 'IMAGE', source: 'builtin://sprites/door' },
    { id: 'libraryMapBackground', kind: 'IMAGE', source: 'builtin://backgrounds/library-room-map' },
    { id: 'dialogueBackground', kind: 'IMAGE', source: 'builtin://backgrounds/visual-novel-library' },
    { id: 'librarianPortrait', kind: 'IMAGE', source: 'builtin://portraits/librarian' },
    ...BUILTIN_PROJECT_ASSETS.filter((asset) => !starterAssetSources.has(asset.source)),
  ],
  scenes: [
    {
      id: 'library',
      type: 'TOP_DOWN',
      name: '도서관 입구',
      width: 16,
      height: 10,
      backgroundAssetId: 'libraryMapBackground',
      tileLayers: [],
      objects: [
        {
          id: 'playerSpawn',
          preset: 'PLAYER_SPAWN',
          position: { x: 8, y: 8 },
          visible: true,
          components: [{ type: 'SPRITE', assetId: 'playerImage' }],
        },
        {
          id: 'librarian',
          preset: 'NPC',
          position: { x: 7, y: 4 },
          visible: true,
          components: [
            { type: 'SPRITE', assetId: 'npcImage' },
            { type: 'INTERACTABLE', prompt: '대화하기' },
          ],
        },
        {
          id: 'libraryKeyObject',
          preset: 'ITEM',
          position: { x: 10, y: 4 },
          visible: true,
          components: [
            { type: 'SPRITE', assetId: 'keyImage' },
            { type: 'PICKUP', itemId: 'libraryKey' },
          ],
        },
        {
          id: 'lockedDoor',
          preset: 'DOOR',
          position: { x: 8, y: 1 },
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
          id: 'talkToLibrarian',
          trigger: { type: 'ON_INTERACT', targetId: 'librarian' },
          conditions: [],
          actions: [{ type: 'SHOW_DIALOGUE', sceneId: 'librarianDialogue' }],
        },
        {
          id: 'takeLibraryKey',
          trigger: { type: 'ON_ENTER', targetId: 'libraryKeyObject' },
          conditions: [],
          actions: [
            { type: 'GIVE_ITEM', itemId: 'libraryKey' },
            { type: 'HIDE_OBJECT', objectId: 'libraryKeyObject' },
          ],
        },
        {
          id: 'openLockedDoor',
          trigger: { type: 'ON_INTERACT', targetId: 'lockedDoor' },
          conditions: [
            { type: 'HAS_ITEM', itemId: 'libraryKey' },
            { type: 'VARIABLE_EQUALS', variableId: 'doorOpened', value: false },
          ],
          actions: [
            { type: 'SET_VARIABLE', variableId: 'doorOpened', value: true },
            { type: 'GO_TO_SCENE', sceneId: 'ending' },
          ],
        },
      ],
    },
    {
      id: 'librarianDialogue',
      type: 'DIALOGUE',
      name: '사서와 대화',
      presentation: 'OVERLAY',
      backgroundAssetId: 'dialogueBackground',
      startNodeId: 'welcome',
      nodes: [
        {
          id: 'welcome',
          speaker: '사서',
          text: '이 도서관의 문은 오래된 황동 열쇠로만 열 수 있어요.',
          portraitAssetId: 'librarianPortrait',
          choices: [
            { id: 'thanks', text: '알겠습니다.', actions: [{ type: 'CLOSE_DIALOGUE' }] },
          ],
        },
      ],
    },
    {
      id: 'ending',
      type: 'DIALOGUE',
      name: '도서관 밖으로',
      presentation: 'FULL_SCREEN',
      backgroundAssetId: 'dialogueBackground',
      startNodeId: 'escaped',
      nodes: [
        {
          id: 'escaped',
          speaker: '안내',
          text: '잠긴 문을 열고 도서관 밖으로 나왔습니다.',
          choices: [
            { id: 'finish', text: '게임 완료', actions: [{ type: 'COMPLETE_GAME' }] },
          ],
        },
      ],
    },
  ],
});
