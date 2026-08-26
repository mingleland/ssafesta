import adventurerWalkSheetUrl from '../../assets/adventurer-walk-4x4.webp';
import dialoguePortraitPackUrl from '../../assets/dialogue-portrait-pack-4x3.webp';
import fantasyLibraryTilesetUrl from '../../assets/fantasy-library-tileset-8x8.webp';
import fantasyObjectAtlasUrl from '../../assets/fantasy-object-atlas-4x4.webp';
import fantasyObjectsExpandedUrl from '../../assets/fantasy-objects-expanded-8x4.webp';
import libraryRoomPreviewUrl from '../../assets/library-room-preview.webp';
import platformerStarterTilesetUrl from '../../assets/platformer-starter-tileset-8x8.webp';
import visualNovelLibraryBackgroundUrl from '../../assets/visual-novel-library-background.webp';
import type { AssetReference } from '../../contracts/gameProject.ts';

export interface AnimationClipDefinition {
  readonly id: string;
  readonly label: string;
  readonly row: number;
  readonly frames: readonly number[];
  readonly fps: number;
  readonly loop: boolean;
}

export interface SpriteSheetDefinition {
  readonly source: string;
  readonly label: string;
  readonly imageUrl: string;
  readonly columns: number;
  readonly rows: number;
  readonly clips: readonly AnimationClipDefinition[];
}

export interface TilesetDefinition {
  readonly source: string;
  readonly label: string;
  readonly imageUrl: string;
  readonly columns: number;
  readonly rows: number;
}

export interface StaticImageDefinition {
  readonly source: string;
  readonly label: string;
  readonly category: 'CHARACTER' | 'OBJECT' | 'PORTRAIT' | 'BACKGROUND';
  readonly imageUrl: string;
  readonly columns: number;
  readonly rows: number;
  readonly frame: number;
}

export const BUILTIN_SPRITE_SHEETS: readonly SpriteSheetDefinition[] = [
  {
    source: 'builtin://sprites/player',
    label: '모험가 · 4방향 이동',
    imageUrl: adventurerWalkSheetUrl,
    columns: 4,
    rows: 4,
    clips: [
      { id: 'walkDown', label: '아래로 걷기', row: 0, frames: [0, 1, 2, 3], fps: 7, loop: true },
      { id: 'walkLeft', label: '왼쪽 걷기', row: 1, frames: [0, 1, 2, 3], fps: 7, loop: true },
      { id: 'walkRight', label: '오른쪽 걷기', row: 2, frames: [0, 1, 2, 3], fps: 7, loop: true },
      { id: 'walkUp', label: '위로 걷기', row: 3, frames: [0, 1, 2, 3], fps: 7, loop: true },
    ],
  },
] as const;

export const findBuiltinSpriteSheet = (source: string): SpriteSheetDefinition | undefined => (
  BUILTIN_SPRITE_SHEETS.find((sheet) => sheet.source === source)
);

export const BUILTIN_TILESETS: readonly TilesetDefinition[] = [
  {
    source: 'builtin://tilesets/library',
    label: '판타지 도서관 · 64 Tiles',
    imageUrl: fantasyLibraryTilesetUrl,
    columns: 8,
    rows: 8,
  },
  {
    source: 'builtin://tilesets/platformer-starter',
    label: '플랫폼 액션 · 64 Tiles',
    imageUrl: platformerStarterTilesetUrl,
    columns: 8,
    rows: 8,
  },
] as const;

export const findBuiltinTileset = (source: string): TilesetDefinition | undefined => (
  BUILTIN_TILESETS.find((tileset) => tileset.source === source)
);

const expandedObject = (
  source: string,
  label: string,
  frame: number,
  category: StaticImageDefinition['category'] = 'OBJECT',
): StaticImageDefinition => ({
  source,
  label,
  category,
  imageUrl: fantasyObjectsExpandedUrl,
  columns: 8,
  rows: 4,
  frame,
});

const libraryObject = (
  source: string,
  label: string,
  frame: number,
  category: StaticImageDefinition['category'] = 'OBJECT',
): StaticImageDefinition => ({
  source,
  label,
  category,
  imageUrl: fantasyObjectAtlasUrl,
  columns: 4,
  rows: 4,
  frame,
});

const portrait = (source: string, label: string, frame: number): StaticImageDefinition => ({
  source,
  label,
  category: 'PORTRAIT',
  imageUrl: dialoguePortraitPackUrl,
  columns: 4,
  rows: 3,
  frame,
});

const platformObject = (source: string, label: string, frame: number): StaticImageDefinition => ({
  source,
  label,
  category: 'OBJECT',
  imageUrl: platformerStarterTilesetUrl,
  columns: 8,
  rows: 8,
  frame,
});

export const BUILTIN_STATIC_IMAGES: readonly StaticImageDefinition[] = [
  {
    source: 'builtin://backgrounds/library-room-map',
    label: '맵 배경 · 비밀 도서관',
    category: 'BACKGROUND',
    imageUrl: libraryRoomPreviewUrl,
    columns: 1,
    rows: 1,
    frame: 0,
  },
  libraryObject('builtin://sprites/npc', 'NPC · 사서', 0, 'CHARACTER'),
  libraryObject('builtin://sprites/key', '황동 열쇠', 1),
  libraryObject('builtin://sprites/door', '닫힌 문', 2),
  expandedObject('builtin://sprites/door-open', '열린 문', 1),
  libraryObject('builtin://sprites/chest', '보물 상자', 3),
  expandedObject('builtin://sprites/chest-open', '열린 상자', 4),
  expandedObject('builtin://sprites/bookshelf', '책장', 5),
  expandedObject('builtin://sprites/table', '테이블', 6),
  expandedObject('builtin://sprites/chair', '의자', 7),
  libraryObject('builtin://sprites/lamp', '가로등', 6),
  libraryObject('builtin://sprites/plant', '화분', 7),
  libraryObject('builtin://sprites/wall', '돌벽', 4),
  libraryObject('builtin://sprites/crate', '나무 상자', 12),
  libraryObject('builtin://sprites/switch-off', '꺼진 스위치', 8),
  expandedObject('builtin://sprites/switch-on', '켜진 스위치', 13),
  expandedObject('builtin://sprites/pressure-plate', '압력판', 14),
  libraryObject('builtin://sprites/spike', '가시 함정', 13),
  expandedObject('builtin://sprites/potion', '회복 물약', 16),
  expandedObject('builtin://sprites/scroll', '두루마리', 17),
  expandedObject('builtin://sprites/gem', '보석', 18),
  libraryObject('builtin://sprites/coin', '코인', 10),
  expandedObject('builtin://sprites/sword', '검', 20),
  expandedObject('builtin://sprites/shield', '방패', 21),
  expandedObject('builtin://sprites/bow', '활', 22),
  expandedObject('builtin://sprites/wand', '마법 지팡이', 23),
  libraryObject('builtin://sprites/portal', '포털', 14),
  expandedObject('builtin://sprites/checkpoint', '체크포인트', 25),
  libraryObject('builtin://sprites/goal', '목표 크리스탈', 9),
  libraryObject('builtin://sprites/interactable', '조사 가능한 책상', 5),
  platformObject('builtin://sprites/platform', '발판', 17),
  platformObject('builtin://sprites/hazard', '가시 함정 · 횡스크롤', 32),
  platformObject('builtin://sprites/enemy-slime', '적 · 슬라임', 52),
  platformObject('builtin://sprites/turret', '발사 포탑', 48),
  platformObject('builtin://sprites/projectile-fireball', '투사체 · 화염탄', 50),
  platformObject('builtin://sprites/checkpoint-platform', '체크포인트 깃발', 36),
  platformObject('builtin://sprites/spawner-platform', '생성 포털', 56),
  platformObject('builtin://sprites/decoration-tree', '장식 · 나무', 61),
  expandedObject('builtin://sprites/clue', '단서', 28),
  expandedObject('builtin://sprites/quest', '퀘스트 마커', 29),
  expandedObject('builtin://sprites/spawn', '스폰 마커', 30),
  expandedObject('builtin://sprites/trigger', '트리거 영역', 31),
  portrait('builtin://portraits/librarian', '사서 · 기본', 0),
  portrait('builtin://portraits/librarian-smile', '사서 · 미소', 1),
  portrait('builtin://portraits/librarian-worried', '사서 · 걱정', 2),
  portrait('builtin://portraits/librarian-surprised', '사서 · 놀람', 3),
  portrait('builtin://portraits/adventurer', '모험가 · 기본', 4),
  portrait('builtin://portraits/adventurer-confident', '모험가 · 자신감', 5),
  portrait('builtin://portraits/adventurer-hurt', '모험가 · 부상', 6),
  portrait('builtin://portraits/adventurer-joy', '모험가 · 기쁨', 7),
  portrait('builtin://portraits/rival', '라이벌 · 기본', 8),
  portrait('builtin://portraits/rival-angry', '라이벌 · 분노', 9),
  portrait('builtin://portraits/rival-shocked', '라이벌 · 충격', 10),
  portrait('builtin://portraits/rival-defeated', '라이벌 · 패배', 11),
  {
    source: 'builtin://backgrounds/visual-novel-library',
    label: '연출 배경 · 마법 도서관',
    category: 'BACKGROUND',
    imageUrl: visualNovelLibraryBackgroundUrl,
    columns: 1,
    rows: 1,
    frame: 0,
  },
] as const;

export const findBuiltinStaticImage = (source: string): StaticImageDefinition | undefined => (
  BUILTIN_STATIC_IMAGES.find((image) => image.source === source)
);

export const isAssetForRole = (
  asset: AssetReference,
  role: StaticImageDefinition['category'],
): boolean => {
  if (asset.kind !== 'IMAGE') return false;
  const builtIn = findBuiltinStaticImage(asset.source);
  if (builtIn !== undefined) return builtIn.category === role;
  return !asset.source.startsWith('builtin://');
};

export const assetDisplayLabel = (asset: AssetReference): string => (
  findBuiltinSpriteSheet(asset.source)?.label
  ?? findBuiltinStaticImage(asset.source)?.label
  ?? findBuiltinTileset(asset.source)?.label
  ?? `내 자산 · ${asset.id}`
);

const builtinAssetId = (source: string): string => `builtin_${source
  .replace('builtin://', '')
  .replace(/[^A-Za-z0-9]+/g, '_')}`;

export const BUILTIN_PROJECT_ASSETS: readonly AssetReference[] = [
  ...BUILTIN_SPRITE_SHEETS.map((sheet) => ({ id: builtinAssetId(sheet.source), kind: 'IMAGE' as const, source: sheet.source })),
  ...BUILTIN_STATIC_IMAGES.map((image) => ({ id: builtinAssetId(image.source), kind: 'IMAGE' as const, source: image.source })),
  ...BUILTIN_TILESETS.map((tileset) => ({ id: builtinAssetId(tileset.source), kind: 'TILESET' as const, source: tileset.source })),
] as const;
