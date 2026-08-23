import { parseGameProject, type Component, type GameObject, type GameProject, type PlatformerScene, type TopDownScene } from '../../contracts/gameProject.ts';
import collectionPreviewUrl from '../../assets/template-collection-preview.webp';
import escapePreviewUrl from '../../assets/template-escape-preview.webp';
import platformerPreviewUrl from '../../assets/template-platformer-preview.webp';
import shooterPreviewUrl from '../../assets/template-shooter-preview.webp';
import storyPreviewUrl from '../../assets/template-story-preview.webp';
import survivalPreviewUrl from '../../assets/template-survival-preview.webp';
import { addPlatformerScene } from './authoringCommands.ts';
import { findPresetDefinition } from './authoringRegistry.ts';
import { createStarterProject } from './createStarterProject.ts';

export type ProjectTemplateId = 'STORY' | 'ESCAPE' | 'COLLECTION' | 'PLATFORMER' | 'SHOOTER' | 'SURVIVAL';

export interface ProjectTemplateDefinition {
  readonly id: ProjectTemplateId;
  readonly previewUrl: string;
  readonly title: string;
  readonly genre: string;
  readonly description: string;
  readonly systems: readonly string[];
  readonly runtimeMode: 'TOP_DOWN' | 'PLATFORMER';
  readonly difficulty: '입문' | '쉬움' | '보통';
  readonly estimatedMinutes: number;
  readonly recommended?: boolean;
}

export const PROJECT_TEMPLATES: readonly ProjectTemplateDefinition[] = [
  { id: 'STORY', previewUrl: storyPreviewUrl, title: '이야기 탐색', genre: '스토리 어드벤처', description: '맵을 돌아다니며 인물과 대화하고 선택으로 이야기를 진행합니다.', systems: ['NPC 대화', '선택지', '장면 이동'], runtimeMode: 'TOP_DOWN', difficulty: '입문', estimatedMinutes: 10, recommended: true },
  { id: 'ESCAPE', previewUrl: escapePreviewUrl, title: '비밀 방탈출', genre: '추리 · 퍼즐', description: '단서를 모으고 조건을 만족해 잠긴 문을 여는 완성 예제입니다.', systems: ['아이템', '조건', '잠긴 문'], runtimeMode: 'TOP_DOWN', difficulty: '쉬움', estimatedMinutes: 15, recommended: true },
  { id: 'COLLECTION', previewUrl: collectionPreviewUrl, title: '보물 수집 퀘스트', genre: '수집 · RPG-lite', description: '세 가지 보물을 모두 모아 목표 지점에 도착하는 게임입니다.', systems: ['인벤토리', '다중 조건', '목표'], runtimeMode: 'TOP_DOWN', difficulty: '쉬움', estimatedMinutes: 15 },
  { id: 'PLATFORMER', previewUrl: platformerPreviewUrl, title: '별빛 점프맵', genre: '플랫폼 액션', description: '중력과 점프로 발판을 건너 목표에 도착하는 횡스크롤 맵입니다.', systems: ['중력', '점프', '발판', '목표'], runtimeMode: 'PLATFORMER', difficulty: '쉬움', estimatedMinutes: 15, recommended: true },
  { id: 'SHOOTER', previewUrl: shooterPreviewUrl, title: '슬라임 블래스터', genre: '횡스크롤 슈팅', description: '이동과 점프, 투사체 발사로 순찰하는 적을 쓰러뜨립니다.', systems: ['체력', '자동 이동', '투사체', '점수'], runtimeMode: 'PLATFORMER', difficulty: '보통', estimatedMinutes: 20 },
  { id: 'SURVIVAL', previewUrl: survivalPreviewUrl, title: '포털 생존전', genre: '생존 웨이브', description: '포털에서 반복 생성되는 적을 상대하며 점수를 올립니다.', systems: ['반복 생성', '추적 적', '체력', '점수'], runtimeMode: 'PLATFORMER', difficulty: '보통', estimatedMinutes: 20 },
] as const;

const assetId = (project: GameProject, source: string): string => {
  const asset = project.assets.find((candidate) => candidate.source === source);
  if (asset === undefined) throw new Error(`template asset missing: ${source}`);
  return asset.id;
};

const sprite = (project: GameProject, source: string, scale = 100, zIndex = 2): Component => ({
  type: 'SPRITE',
  assetId: assetId(project, source),
  scale,
  zIndex,
});

const templateObject = (
  project: GameProject,
  id: string,
  preset: GameObject['preset'],
  x: number,
  y: number,
  source: string,
  components: readonly Component[] = [],
  visible = true,
): GameObject => ({
  id,
  preset,
  position: { x, y },
  visible,
  components: [sprite(project, source), ...components],
});

const createStoryProject = (gameId: number): GameProject => {
  const base = createStarterProject(gameId);
  const player = templateObject(base, 'playerSpawn', 'PLAYER_SPAWN', 8, 8, 'builtin://sprites/player', [], true);
  const guide = templateObject(base, 'villageGuide', 'NPC', 5, 5, 'builtin://sprites/npc', [{ type: 'INTERACTABLE', prompt: '이야기 듣기' }]);
  const friend = templateObject(base, 'waitingFriend', 'NPC', 11, 4, 'builtin://sprites/npc', [{ type: 'INTERACTABLE', prompt: '약속 확인하기' }]);
  const gate = templateObject(base, 'moonGate', 'GOAL', 8, 1, 'builtin://sprites/portal', [{ type: 'INTERACTABLE', prompt: '달빛 언덕으로 가기' }]);
  const decorations = [
    templateObject(base, 'storyTree1', 'DECORATION', 2, 2, 'builtin://sprites/decoration-tree'),
    templateObject(base, 'storyTree2', 'DECORATION', 13, 7, 'builtin://sprites/decoration-tree'),
    templateObject(base, 'storyLamp1', 'DECORATION', 4, 2, 'builtin://sprites/lamp'),
    templateObject(base, 'storyLamp2', 'DECORATION', 12, 2, 'builtin://sprites/lamp'),
    templateObject(base, 'storyTable', 'DECORATION', 8, 5, 'builtin://sprites/table'),
    templateObject(base, 'storyQuest', 'INTERACTABLE', 8, 3, 'builtin://sprites/quest', [{ type: 'INTERACTABLE', prompt: '마을 게시판 읽기' }]),
  ];
  return parseGameProject({
    ...base,
    title: '별빛 마을의 약속',
    variables: [{ id: 'promiseAccepted', type: 'BOOLEAN', initialValue: false }],
    items: [],
    startSceneId: 'villageSquare',
    scenes: [
      {
        id: 'villageSquare',
        type: 'TOP_DOWN',
        name: '별빛 마을 광장',
        width: 16,
        height: 10,
        backgroundAssetId: 'libraryMapBackground',
        tileLayers: [],
        objects: [player, guide, friend, gate, ...decorations],
        events: [
          { id: 'hearVillageStory', trigger: { type: 'ON_INTERACT', targetId: guide.id }, conditions: [], actions: [{ type: 'SHOW_DIALOGUE', sceneId: 'guideDialogue' }] },
          { id: 'makePromise', trigger: { type: 'ON_INTERACT', targetId: friend.id }, conditions: [], actions: [{ type: 'SHOW_DIALOGUE', sceneId: 'promiseDialogue' }] },
          { id: 'readQuestBoard', trigger: { type: 'ON_INTERACT', targetId: 'storyQuest' }, conditions: [], actions: [{ type: 'SHOW_DIALOGUE', sceneId: 'boardDialogue' }] },
          { id: 'finishStory', trigger: { type: 'ON_ENTER', targetId: gate.id }, conditions: [{ type: 'VARIABLE_EQUALS', variableId: 'promiseAccepted', value: true }], actions: [{ type: 'GO_TO_SCENE', sceneId: 'storyEnding' }] },
        ],
      },
      {
        id: 'guideDialogue',
        type: 'DIALOGUE',
        name: '마을의 전설',
        presentation: 'OVERLAY',
        backgroundAssetId: 'dialogueBackground',
        startNodeId: 'guideWelcome',
        nodes: [
          { id: 'guideWelcome', speaker: '마을 안내인', text: '오늘 밤 달빛 언덕에서 별의 문이 열려요. 함께 갈 사람을 찾아보세요.', portraitAssetId: 'librarianPortrait', choices: [{ id: 'askLegend', text: '별의 문에 대해 더 묻는다', nextNodeId: 'guideLegend', actions: [] }, { id: 'leaveGuide', text: '고맙다고 인사한다', actions: [{ type: 'CLOSE_DIALOGUE' }] }] },
          { id: 'guideLegend', speaker: '마을 안내인', text: '진심으로 맺은 약속이 있어야 문이 반응한다고 전해져요.', portraitAssetId: assetId(base, 'builtin://portraits/librarian-smile'), choices: [{ id: 'understandLegend', text: '약속할 사람을 찾아본다', actions: [{ type: 'CLOSE_DIALOGUE' }] }] },
        ],
      },
      {
        id: 'promiseDialogue',
        type: 'DIALOGUE',
        name: '언덕에서의 약속',
        presentation: 'OVERLAY',
        backgroundAssetId: 'dialogueBackground',
        startNodeId: 'friendQuestion',
        nodes: [{ id: 'friendQuestion', speaker: '소연', text: '별의 문이 열리면 같이 소원을 빌러 갈래?', portraitAssetId: assetId(base, 'builtin://portraits/adventurer'), choices: [{ id: 'acceptPromise', text: '함께 가자고 약속한다', actions: [{ type: 'SET_VARIABLE', variableId: 'promiseAccepted', value: true }, { type: 'CLOSE_DIALOGUE' }] }, { id: 'delayPromise', text: '조금 더 생각해 본다', actions: [{ type: 'CLOSE_DIALOGUE' }] }] }],
      },
      {
        id: 'boardDialogue',
        type: 'DIALOGUE',
        name: '마을 게시판',
        presentation: 'OVERLAY',
        startNodeId: 'boardNotice',
        nodes: [{ id: 'boardNotice', speaker: '게시판', text: '목표: 소연과 약속한 뒤 북쪽의 달빛 문으로 이동하세요.', choices: [{ id: 'closeBoard', text: '확인했다', actions: [{ type: 'CLOSE_DIALOGUE' }] }] }],
      },
      {
        id: 'storyEnding',
        type: 'DIALOGUE',
        name: '별의 문 너머',
        presentation: 'FULL_SCREEN',
        backgroundAssetId: 'dialogueBackground',
        startNodeId: 'storyFinale',
        nodes: [{ id: 'storyFinale', speaker: '소연', text: '약속대로 와 줬구나. 이제 우리만의 소원을 빌어 보자.', portraitAssetId: assetId(base, 'builtin://portraits/adventurer-joy'), choices: [{ id: 'finishStoryChoice', text: '함께 별을 바라본다', actions: [{ type: 'COMPLETE_GAME' }] }] }],
      },
    ],
  });
};

const createEscapeProject = (gameId: number): GameProject => {
  const base = createStarterProject(gameId);
  const keyItem = { id: 'archiveKey', name: '기록 보관실 열쇠', assetId: assetId(base, 'builtin://sprites/key') } as const;
  const studyObjects: GameObject[] = [
    templateObject(base, 'playerSpawn', 'PLAYER_SPAWN', 2, 8, 'builtin://sprites/player'),
    templateObject(base, 'clueDesk', 'INTERACTABLE', 6, 4, 'builtin://sprites/interactable', [{ type: 'INTERACTABLE', prompt: '찢어진 메모 읽기' }]),
    templateObject(base, 'keyChest', 'INTERACTABLE', 12, 6, 'builtin://sprites/chest', [{ type: 'INTERACTABLE', prompt: '상자 열기' }]),
    templateObject(base, 'archiveDoor', 'DOOR', 14, 2, 'builtin://sprites/door', [{ type: 'COLLIDER', solid: true }, { type: 'INTERACTABLE', prompt: '보관실 열기' }]),
    templateObject(base, 'studyShelf1', 'WALL', 3, 2, 'builtin://sprites/bookshelf', [{ type: 'COLLIDER', solid: true }]),
    templateObject(base, 'studyShelf2', 'WALL', 6, 2, 'builtin://sprites/bookshelf', [{ type: 'COLLIDER', solid: true }]),
    templateObject(base, 'studyTable', 'WALL', 9, 5, 'builtin://sprites/table', [{ type: 'COLLIDER', solid: true }]),
    templateObject(base, 'studyLamp', 'DECORATION', 11, 3, 'builtin://sprites/lamp'),
  ];
  const vaultObjects: GameObject[] = [
    templateObject(base, 'vaultSpawn', 'PLAYER_SPAWN', 2, 7, 'builtin://sprites/player'),
    templateObject(base, 'powerSwitch', 'INTERACTABLE', 7, 4, 'builtin://sprites/switch-off', [{ type: 'INTERACTABLE', prompt: '전원 스위치 켜기' }]),
    templateObject(base, 'escapePortal', 'GOAL', 13, 2, 'builtin://sprites/portal', [{ type: 'INTERACTABLE', prompt: '비상 통로 사용' }], false),
    templateObject(base, 'vaultSpike1', 'HAZARD', 6, 7, 'builtin://sprites/spike', [{ type: 'DAMAGE', amount: 1 }]),
    templateObject(base, 'vaultSpike2', 'HAZARD', 9, 7, 'builtin://sprites/spike', [{ type: 'DAMAGE', amount: 1 }]),
    templateObject(base, 'vaultCrate1', 'WALL', 5, 5, 'builtin://sprites/crate', [{ type: 'COLLIDER', solid: true }]),
    templateObject(base, 'vaultCrate2', 'WALL', 10, 4, 'builtin://sprites/crate', [{ type: 'COLLIDER', solid: true }]),
  ];
  return parseGameProject({
    ...base,
    title: '기록 보관실 탈출',
    variables: [{ id: 'powerRestored', type: 'BOOLEAN', initialValue: false }],
    items: [keyItem],
    startSceneId: 'sealedStudy',
    scenes: [
      {
        id: 'sealedStudy', type: 'TOP_DOWN', name: '잠긴 서재', width: 16, height: 10,
        backgroundAssetId: 'libraryMapBackground', tileLayers: [], objects: studyObjects,
        events: [
          { id: 'readEscapeClue', trigger: { type: 'ON_INTERACT', targetId: 'clueDesk' }, conditions: [], actions: [{ type: 'SHOW_DIALOGUE', sceneId: 'clueDialogue' }] },
          { id: 'takeArchiveKey', trigger: { type: 'ON_INTERACT', targetId: 'keyChest' }, conditions: [], actions: [{ type: 'GIVE_ITEM', itemId: keyItem.id }, { type: 'HIDE_OBJECT', objectId: 'keyChest' }] },
          { id: 'unlockArchive', trigger: { type: 'ON_INTERACT', targetId: 'archiveDoor' }, conditions: [{ type: 'HAS_ITEM', itemId: keyItem.id }], actions: [{ type: 'GO_TO_SCENE', sceneId: 'darkVault' }] },
        ],
      },
      {
        id: 'darkVault', type: 'TOP_DOWN', name: '어두운 보관실', width: 16, height: 10,
        backgroundAssetId: 'libraryMapBackground', tileLayers: [], objects: vaultObjects,
        events: [
          { id: 'restorePower', trigger: { type: 'ON_INTERACT', targetId: 'powerSwitch' }, conditions: [{ type: 'VARIABLE_EQUALS', variableId: 'powerRestored', value: false }], actions: [{ type: 'SET_VARIABLE', variableId: 'powerRestored', value: true }, { type: 'SHOW_OBJECT', objectId: 'escapePortal' }] },
          { id: 'leaveArchive', trigger: { type: 'ON_ENTER', targetId: 'escapePortal' }, conditions: [{ type: 'VARIABLE_EQUALS', variableId: 'powerRestored', value: true }], actions: [{ type: 'GO_TO_SCENE', sceneId: 'escapeEnding' }] },
        ],
      },
      {
        id: 'clueDialogue', type: 'DIALOGUE', name: '찢어진 메모', presentation: 'OVERLAY', backgroundAssetId: 'dialogueBackground', startNodeId: 'clueText',
        nodes: [{ id: 'clueText', speaker: '메모', text: '열쇠는 오래된 상자 안에, 비상 통로는 보관실 전원을 복구해야 나타난다.', choices: [{ id: 'closeClue', text: '단서를 기억한다', actions: [{ type: 'CLOSE_DIALOGUE' }] }] }],
      },
      {
        id: 'escapeEnding', type: 'DIALOGUE', name: '탈출 성공', presentation: 'FULL_SCREEN', backgroundAssetId: 'dialogueBackground', startNodeId: 'escapeFinale',
        nodes: [{ id: 'escapeFinale', speaker: '안내', text: '전원을 복구하고 비상 통로를 찾아 기록 보관실에서 탈출했습니다.', choices: [{ id: 'finishEscape', text: '게임 완료', actions: [{ type: 'COMPLETE_GAME' }] }] }],
      },
    ],
  });
};

const platformProject = (gameId: number, title: string): { project: GameProject; scene: PlatformerScene } => {
  const added = addPlatformerScene(createStarterProject(gameId));
  const scene = added.scenes.at(-1);
  if (scene?.type !== 'PLATFORMER') throw new Error('platform template scene missing');
  const project = parseGameProject({ ...added, title, startSceneId: scene.id, scenes: [{ ...scene, name: title }] });
  return { project, scene: project.scenes[0] as PlatformerScene };
};

const createPlatformerProject = (gameId: number): GameProject => {
  const { project, scene } = platformProject(gameId, '별빛 점프맵');
  const elevatedPositions = [
    ...[7, 8, 9, 10].map((x) => ({ x, y: 9 })),
    ...[13, 14, 15, 16].map((x) => ({ x, y: 7 })),
    ...[18, 19, 20, 21, 22].map((x) => ({ x, y: 5 })),
  ];
  const elevated: GameObject[] = elevatedPositions.map((position, index) => ({
    id: `jumpPlatform${index + 1}`,
    preset: 'PLATFORM',
    position,
    visible: true,
    components: findPresetDefinition('PLATFORM').createComponents(project),
  }));
  const hazards: GameObject[] = [5, 12, 17].map((x, index) => ({
    id: `jumpHazard${index + 1}`,
    preset: 'HAZARD',
    position: { x, y: 10 },
    visible: true,
    components: findPresetDefinition('HAZARD').createComponents(project),
  }));
  const checkpoint: GameObject = {
    id: 'jumpCheckpoint',
    preset: 'CHECKPOINT',
    position: { x: 14, y: 6 },
    visible: true,
    components: findPresetDefinition('CHECKPOINT').createComponents(project),
  };
  return parseGameProject({
    ...project,
    scenes: [{
      ...scene,
      objects: [
        ...scene.objects.map((object) => object.preset === 'GOAL' ? { ...object, position: { x: 21, y: 4 } } : object),
        ...elevated,
        ...hazards,
        checkpoint,
      ],
    }],
  });
};

const createCollectionProject = (gameId: number): GameProject => {
  const base = createStarterProject(gameId);
  const scene = base.scenes.find((candidate) => candidate.type === 'TOP_DOWN');
  if (scene?.type !== 'TOP_DOWN') throw new Error('collection template scene missing');
  const items = [
    { id: 'moonGem', name: '달빛 보석', assetId: assetId(base, 'builtin://sprites/gem') },
    { id: 'ancientScroll', name: '고대 두루마리', assetId: assetId(base, 'builtin://sprites/scroll') },
    { id: 'royalCoin', name: '왕실 코인', assetId: assetId(base, 'builtin://sprites/coin') },
  ] as const;
  const pickups: GameObject[] = items.map((item, index) => ({
    id: `treasure${index + 1}`,
    preset: 'ITEM',
    position: { x: 3 + index * 5, y: 6 - (index % 2) * 2 },
    visible: true,
    components: [{ type: 'SPRITE', assetId: item.assetId, scale: 105, zIndex: 3 }, { type: 'PICKUP', itemId: item.id }, { type: 'SCORE_VALUE', value: 100 + index * 50 }],
  }));
  const goalId = 'collectionGoal';
  const collectionScene: TopDownScene = {
    ...scene,
    name: '별빛 정원',
    objects: [
      ...scene.objects.filter((object) => object.preset === 'PLAYER_SPAWN').map((object) => ({ ...object, position: { x: 2, y: 8 } })),
      ...pickups,
      { id: goalId, preset: 'GOAL', position: { x: 14, y: 1 }, visible: true, components: [{ type: 'SPRITE', assetId: assetId(base, 'builtin://sprites/goal'), scale: 120, zIndex: 3 }] },
      templateObject(base, 'gardenTree1', 'DECORATION', 1, 2, 'builtin://sprites/decoration-tree'),
      templateObject(base, 'gardenTree2', 'DECORATION', 14, 7, 'builtin://sprites/decoration-tree'),
      templateObject(base, 'gardenLamp1', 'DECORATION', 4, 7, 'builtin://sprites/lamp'),
      templateObject(base, 'gardenLamp2', 'DECORATION', 11, 3, 'builtin://sprites/lamp'),
      templateObject(base, 'gardenWall1', 'WALL', 7, 5, 'builtin://sprites/wall', [{ type: 'COLLIDER', solid: true }]),
      templateObject(base, 'gardenWall2', 'WALL', 8, 5, 'builtin://sprites/wall', [{ type: 'COLLIDER', solid: true }]),
    ],
    events: [
      ...pickups.map((object, index) => ({ id: `collectTreasure${index + 1}`, trigger: { type: 'ON_ENTER' as const, targetId: object.id }, conditions: [], actions: [{ type: 'GIVE_ITEM' as const, itemId: items[index]?.id ?? items[0].id }, { type: 'HIDE_OBJECT' as const, objectId: object.id }] })),
      { id: 'finishCollection', trigger: { type: 'ON_ENTER', targetId: goalId }, conditions: items.map((item) => ({ type: 'HAS_ITEM' as const, itemId: item.id })), actions: [{ type: 'COMPLETE_GAME' }] },
    ],
  };
  return parseGameProject({ ...base, title: '별빛 보물 수집', items, scenes: [collectionScene] });
};

const createActionProject = (gameId: number, id: 'SHOOTER' | 'SURVIVAL'): GameProject => {
  const { project, scene } = platformProject(gameId, id === 'SHOOTER' ? '슬라임 블래스터' : '포털 생존전');
  const projectileAssetId = assetId(project, 'builtin://sprites/projectile-fireball');
  const playerObjects = scene.objects.map((object) => object.preset === 'PLAYER_SPAWN'
    ? { ...object, components: [...object.components, { type: 'SHOOTER' as const, projectileAssetId, damage: 1, cooldownMs: 360 }] }
    : object);
  const additions: GameObject[] = id === 'SHOOTER'
    ? [
        ...[5, 10, 15].map((x, index) => ({ id: `slime${index + 1}`, preset: 'ENEMY' as const, position: { x, y: 10 }, visible: true, components: findPresetDefinition('ENEMY').createComponents(project) })),
        ...[8, 9, 13, 14, 18, 19].map((x, index) => ({ id: `blasterPlatform${index + 1}`, preset: 'PLATFORM' as const, position: { x, y: index < 2 ? 8 : index < 4 ? 7 : 6 }, visible: true, components: findPresetDefinition('PLATFORM').createComponents(project) })),
        { id: 'enemyTurret', preset: 'TURRET', position: { x: 19, y: 5 }, visible: true, components: findPresetDefinition('TURRET').createComponents(project) },
        { id: 'blasterCheckpoint', preset: 'CHECKPOINT', position: { x: 13, y: 6 }, visible: true, components: findPresetDefinition('CHECKPOINT').createComponents(project) },
      ]
    : [
        { id: 'wavePortal', preset: 'SPAWNER', position: { x: 18, y: 10 }, visible: true, components: findPresetDefinition('SPAWNER').createComponents(project) },
        { id: 'arenaTurret', preset: 'TURRET', position: { x: 21, y: 10 }, visible: true, components: findPresetDefinition('TURRET').createComponents(project) },
        { id: 'arenaCheckpoint', preset: 'CHECKPOINT', position: { x: 11, y: 10 }, visible: true, components: findPresetDefinition('CHECKPOINT').createComponents(project) },
        ...[7, 15].map((x, index) => ({ id: `arenaHazard${index + 1}`, preset: 'HAZARD' as const, position: { x, y: 10 }, visible: true, components: findPresetDefinition('HAZARD').createComponents(project) })),
        ...[5, 6, 10, 11, 12, 16, 17].map((x, index) => ({ id: `arenaPlatform${index + 1}`, preset: 'PLATFORM' as const, position: { x, y: index < 2 ? 8 : index < 5 ? 7 : 8 }, visible: true, components: findPresetDefinition('PLATFORM').createComponents(project) })),
      ];
  return parseGameProject({
    ...project,
    scenes: [{
      ...scene,
      name: id === 'SHOOTER' ? '슬라임 연구소 사격장' : '차원 균열 생존 아레나',
      objects: [
        ...playerObjects.map((object) => object.preset === 'GOAL'
          ? { ...object, position: { x: 22, y: id === 'SHOOTER' ? 10 : 9 } }
          : object),
        ...additions,
      ],
    }],
  });
};

export const createProjectFromTemplate = (gameId: number, templateId: ProjectTemplateId): GameProject => {
  if (templateId === 'STORY') return createStoryProject(gameId);
  if (templateId === 'ESCAPE') return createEscapeProject(gameId);
  if (templateId === 'COLLECTION') return createCollectionProject(gameId);
  if (templateId === 'PLATFORMER') return createPlatformerProject(gameId);
  return createActionProject(gameId, templateId);
};
