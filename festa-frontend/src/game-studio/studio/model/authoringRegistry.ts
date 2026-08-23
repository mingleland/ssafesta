import type {
  Action,
  Component,
  Condition,
  GameObject,
  GameProject,
} from '../../contracts/gameProject.ts';

export interface PresetDefinition {
  readonly type: GameObject['preset'];
  readonly category: '기본' | '캐릭터' | '상호작용' | '액션' | '장식';
  readonly previewSource?: string;
  readonly label: string;
  readonly icon: string;
  readonly description: string;
  createComponents(project: GameProject): readonly Component[];
}

const firstItemId = (project: GameProject): string | undefined => project.items[0]?.id;
const imageComponent = (project: GameProject, source: string): Component[] => {
  const asset = project.assets.find((candidate) => candidate.kind === 'IMAGE' && candidate.source === source);
  return asset === undefined ? [] : [{ type: 'SPRITE', assetId: asset.id, scale: 100, zIndex: 2 }];
};
const imageAssetId = (project: GameProject, source: string): string | undefined => (
  project.assets.find((candidate) => candidate.kind === 'IMAGE' && candidate.source === source)?.id
);

export const PRESET_DEFINITIONS: readonly PresetDefinition[] = [
  {
    type: 'PLAYER_SPAWN',
    category: '기본',
    label: '플레이어 시작점',
    icon: '◆',
    description: 'Scene에 하나만 둘 수 있는 시작 위치',
    createComponents: () => [],
  },
  {
    type: 'NPC',
    category: '캐릭터',
    previewSource: 'builtin://sprites/npc',
    label: 'NPC',
    icon: '●',
    description: '대화와 이벤트를 연결하는 캐릭터',
    createComponents: (project) => [...imageComponent(project, 'builtin://sprites/npc'), { type: 'INTERACTABLE', prompt: '대화하기' }],
  },
  {
    type: 'WALL',
    category: '기본',
    previewSource: 'builtin://sprites/wall',
    label: '벽 / 장애물',
    icon: '▦',
    description: '이동을 막는 충돌 오브젝트',
    createComponents: (project) => [...imageComponent(project, 'builtin://sprites/wall'), { type: 'COLLIDER', solid: true }],
  },
  {
    type: 'INTERACTABLE',
    category: '상호작용',
    previewSource: 'builtin://sprites/interactable',
    label: '상호작용 오브젝트',
    icon: '✦',
    description: '책상, 상자, 스위치처럼 이벤트를 붙이는 범용 오브젝트',
    createComponents: (project) => [...imageComponent(project, 'builtin://sprites/interactable'), { type: 'INTERACTABLE', prompt: '조사하기' }],
  },
  {
    type: 'ITEM',
    category: '상호작용',
    previewSource: 'builtin://sprites/coin',
    label: '아이템',
    icon: '◇',
    description: '인벤토리에 들어가는 수집 오브젝트',
    createComponents: (project) => {
      const itemId = firstItemId(project);
      return [
        ...imageComponent(project, 'builtin://sprites/coin'),
        ...(itemId === undefined ? [] : [{ type: 'PICKUP' as const, itemId }]),
      ];
    },
  },
  {
    type: 'DOOR',
    category: '상호작용',
    previewSource: 'builtin://sprites/door',
    label: '문 / 텔레포트',
    icon: '▯',
    description: '조건에 따라 다른 Scene으로 이동시키는 오브젝트',
    createComponents: (project) => [
      ...imageComponent(project, 'builtin://sprites/door'),
      { type: 'COLLIDER', solid: true },
      { type: 'INTERACTABLE', prompt: '열기' },
    ],
  },
  {
    type: 'GOAL',
    category: '상호작용',
    previewSource: 'builtin://sprites/goal',
    label: '목표 지점',
    icon: '★',
    description: '게임 완료 이벤트를 연결하는 목표',
    createComponents: (project) => [...imageComponent(project, 'builtin://sprites/goal'), { type: 'INTERACTABLE', prompt: '도착하기' }],
  },
  {
    type: 'PLATFORM',
    category: '기본',
    previewSource: 'builtin://sprites/platform',
    label: '발판',
    icon: '▰',
    description: '플랫폼 장면에서 플레이어가 올라서는 발판',
    createComponents: (project) => [...imageComponent(project, 'builtin://sprites/platform'), { type: 'COLLIDER', solid: true }],
  },
  {
    type: 'HAZARD',
    category: '액션',
    previewSource: 'builtin://sprites/hazard',
    label: '함정',
    icon: '▲',
    description: '닿았을 때 피해나 장면 재시작 이벤트를 연결하는 위험 요소',
    createComponents: (project) => [...imageComponent(project, 'builtin://sprites/hazard'), { type: 'DAMAGE', amount: 1 }],
  },
  {
    type: 'ENEMY',
    category: '캐릭터',
    previewSource: 'builtin://sprites/enemy-slime',
    label: '적 캐릭터',
    icon: '♟',
    description: '전투, 추적, 점수 이벤트를 연결하는 적',
    createComponents: (project) => [
      ...imageComponent(project, 'builtin://sprites/enemy-slime'),
      { type: 'HEALTH', max: 3 },
      { type: 'DAMAGE', amount: 1 },
      { type: 'AUTO_MOVE', axis: 'HORIZONTAL', speed: 2, range: 4 },
    ],
  },
  {
    type: 'TURRET',
    category: '액션',
    previewSource: 'builtin://sprites/turret',
    label: '발사 장치',
    icon: '◉',
    description: '투사체를 발사하는 슈팅·디펜스용 장치',
    createComponents: (project) => {
      const projectileAssetId = imageAssetId(project, 'builtin://sprites/projectile-fireball');
      return [
        ...imageComponent(project, 'builtin://sprites/turret'),
        ...(projectileAssetId === undefined ? [] : [{ type: 'SHOOTER' as const, projectileAssetId, damage: 1, cooldownMs: 900 }]),
      ];
    },
  },
  {
    type: 'CHECKPOINT',
    category: '액션',
    previewSource: 'builtin://sprites/checkpoint-platform',
    label: '체크포인트',
    icon: '⚑',
    description: '실패 후 돌아올 위치를 표시하는 지점',
    createComponents: (project) => [...imageComponent(project, 'builtin://sprites/checkpoint-platform'), { type: 'CHECKPOINT' }],
  },
  {
    type: 'SPAWNER',
    category: '액션',
    previewSource: 'builtin://sprites/spawner-platform',
    label: '생성기',
    icon: '◎',
    description: '적이나 아이템을 반복 생성하는 웨이브용 오브젝트',
    createComponents: (project) => {
      const enemyAssetId = imageAssetId(project, 'builtin://sprites/enemy-slime');
      return [
        ...imageComponent(project, 'builtin://sprites/spawner-platform'),
        ...(enemyAssetId === undefined ? [] : [{ type: 'SPAWNER' as const, enemyAssetId, intervalMs: 3000, maxAlive: 5 }]),
      ];
    },
  },
  {
    type: 'DECORATION',
    category: '장식',
    previewSource: 'builtin://sprites/decoration-tree',
    label: '장식',
    icon: '♣',
    description: '충돌 없이 분위기를 만드는 배경 오브젝트',
    createComponents: (project) => imageComponent(project, 'builtin://sprites/decoration-tree'),
  },
] as const;

export const COMPONENT_LABELS: Readonly<Record<Component['type'], string>> = {
  SPRITE: '모습',
  COLLIDER: '충돌',
  INTERACTABLE: '상호작용',
  PICKUP: '아이템 획득',
  DAMAGE: '피해',
  HEALTH: '체력',
  SCORE_VALUE: '점수',
  CHECKPOINT: '체크포인트',
  AUTO_MOVE: '자동 이동',
  SHOOTER: '투사체 발사',
  SPAWNER: '반복 생성',
};

export const CONDITION_LABELS: Readonly<Record<Condition['type'], string>> = {
  VARIABLE_EQUALS: '변수 값 비교',
  HAS_ITEM: '아이템 보유',
};

export const ACTION_LABELS: Readonly<Record<Action['type'], string>> = {
  SHOW_DIALOGUE: '대화 표시',
  SET_VARIABLE: '변수 변경',
  GIVE_ITEM: '아이템 지급',
  REMOVE_ITEM: '아이템 제거',
  SHOW_OBJECT: '오브젝트 표시',
  HIDE_OBJECT: '오브젝트 숨김',
  GO_TO_SCENE: 'Scene 이동',
  COMPLETE_GAME: '게임 완료',
  CLOSE_DIALOGUE: '대화 닫기',
};

export const TOP_DOWN_ACTION_TYPES: readonly Action['type'][] = [
  'SET_VARIABLE',
  'GIVE_ITEM',
  'REMOVE_ITEM',
  'SHOW_OBJECT',
  'HIDE_OBJECT',
  'SHOW_DIALOGUE',
  'GO_TO_SCENE',
  'COMPLETE_GAME',
];

export const findPresetDefinition = (
  preset: GameObject['preset'],
): PresetDefinition => {
  const definition = PRESET_DEFINITIONS.find((candidate) => candidate.type === preset);
  if (definition === undefined) throw new Error(`Unknown preset: ${preset}`);
  return definition;
};
