import { parseGameProject, type GameObject, type GameProject, type PlatformerScene, type TopDownScene } from '../../contracts/gameProject.ts';
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
    components: [{ type: 'SPRITE', assetId: item.assetId, scale: 105, zIndex: 3 }, { type: 'PICKUP', itemId: item.id }],
  }));
  const goalId = 'collectionGoal';
  const collectionScene: TopDownScene = {
    ...scene,
    name: '별빛 정원',
    objects: [
      ...scene.objects.filter((object) => object.preset === 'PLAYER_SPAWN'),
      ...pickups,
      { id: goalId, preset: 'GOAL', position: { x: 14, y: 1 }, visible: true, components: [{ type: 'SPRITE', assetId: assetId(base, 'builtin://sprites/goal'), scale: 120, zIndex: 3 }] },
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
    ? [5, 10, 15].map((x, index) => ({ id: `slime${index + 1}`, preset: 'ENEMY', position: { x, y: 10 }, visible: true, components: findPresetDefinition('ENEMY').createComponents(project) }))
    : [{ id: 'wavePortal', preset: 'SPAWNER', position: { x: 18, y: 10 }, visible: true, components: findPresetDefinition('SPAWNER').createComponents(project) }];
  return parseGameProject({
    ...project,
    scenes: [{ ...scene, objects: [...playerObjects, ...additions] }],
  });
};

export const createProjectFromTemplate = (gameId: number, templateId: ProjectTemplateId): GameProject => {
  if (templateId === 'STORY') return parseGameProject({ ...createStarterProject(gameId), title: '별빛 도서관의 약속' });
  if (templateId === 'ESCAPE') return parseGameProject({ ...createStarterProject(gameId), title: '비밀 도서관 탈출' });
  if (templateId === 'COLLECTION') return createCollectionProject(gameId);
  if (templateId === 'PLATFORMER') return createPlatformerProject(gameId);
  return createActionProject(gameId, templateId);
};
