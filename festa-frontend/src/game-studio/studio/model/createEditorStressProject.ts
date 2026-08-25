import { parseGameProject, type GameObject, type GameProject } from '../../contracts/gameProject.ts';
import { createStarterProject } from './createStarterProject.ts';

export const createEditorStressProject = (gameId: number): GameProject => {
  const base = createStarterProject(gameId);
  const library = base.scenes.find((scene) => scene.id === 'library');
  if (library?.type !== 'TOP_DOWN') throw new Error('성능 검증용 시작 Scene을 만들 수 없습니다.');

  const additions: GameObject[] = Array.from({ length: 500 - library.objects.length }, (_, index) => ({
    id: `stressObject${index + 1}`,
    preset: 'DECORATION',
    position: {
      x: (index * 17) % 100,
      y: (Math.floor(index / 5) * 13 + index) % 100,
    },
    visible: true,
    components: [{
      type: 'SPRITE',
      assetId: index % 3 === 0 ? 'npcImage' : index % 3 === 1 ? 'keyImage' : 'doorImage',
      scale: 100,
      zIndex: 2,
    }],
  }));

  return parseGameProject({
    ...base,
    title: '최대 프로젝트 편집 검증',
    scenes: base.scenes.map((scene) => scene.id === library.id ? {
      ...library,
      name: '100 × 100 성능 검증 맵',
      width: 100,
      height: 100,
      backgroundAssetId: undefined,
      tileLayers: [{
        id: 'stressTiles',
        name: '10,000칸 타일',
        tilesetAssetId: 'libraryTiles',
        data: Array.from({ length: 10_000 }, (_, index) => index % 16),
      }],
      objects: [...library.objects, ...additions],
    } : scene),
  });
};
