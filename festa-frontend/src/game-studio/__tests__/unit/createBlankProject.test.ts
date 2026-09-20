// S15P21A604-481 — createBlankProject가 Notion QA id=25에서 합의한 "완전히 빈 프로젝트"
// 정의를 정확히 만족하는지 검증한다: 씬 1개, PLAYER_SPAWN 1개(컴포넌트 없음), 빌트인 에셋
// 카탈로그 전체 유지, 그 외(오브젝트/이벤트/아이템/변수/타일/대화 씬)는 전부 없음.
import { describe, expect, it } from 'vitest';
import { parseGameProject } from '../../contracts/gameProject.ts';
import {
  BLANK_PROJECT_PLAYER_SPAWN_ID,
  BLANK_PROJECT_SCENE_ID,
  createBlankProject,
} from '../../studio/model/createBlankProject.ts';
import { BUILTIN_PROJECT_ASSETS } from '../../studio/assets/builtinAssetCatalog.ts';

describe('createBlankProject', () => {
  it('계약(GameProject 스키마)을 통과한다', () => {
    const project = createBlankProject(901);
    expect(parseGameProject(project)).toBe(project);
  });

  it('씬이 정확히 1개(TOP_DOWN)이고 시작 Scene으로 지정돼 있다', () => {
    const project = createBlankProject(901);
    expect(project.scenes).toHaveLength(1);
    const scene = project.scenes[0];
    expect(scene?.id).toBe(BLANK_PROJECT_SCENE_ID);
    expect(scene?.type).toBe('TOP_DOWN');
    expect(project.startSceneId).toBe(BLANK_PROJECT_SCENE_ID);
  });

  it('PLAYER_SPAWN 오브젝트가 정확히 1개고, 컴포넌트(스프라이트 포함)는 없다', () => {
    const project = createBlankProject(901);
    const scene = project.scenes[0];
    if (scene?.type !== 'TOP_DOWN') throw new Error('expected TOP_DOWN scene');
    expect(scene.objects).toHaveLength(1);
    expect(scene.objects[0]?.id).toBe(BLANK_PROJECT_PLAYER_SPAWN_ID);
    expect(scene.objects[0]?.preset).toBe('PLAYER_SPAWN');
    expect(scene.objects[0]?.components).toHaveLength(0);
  });

  it('빌트인 에셋 카탈로그를 전부 등록해 이후 오브젝트 배치가 막히지 않는다', () => {
    const project = createBlankProject(901);
    expect(project.assets).toHaveLength(BUILTIN_PROJECT_ASSETS.length);
    expect(project.assets.some((asset) => asset.kind === 'IMAGE')).toBe(true);
  });

  it('그 외(이벤트/아이템/변수/타일/대화 씬)는 전부 비어 있다', () => {
    const project = createBlankProject(901);
    const scene = project.scenes[0];
    if (scene?.type !== 'TOP_DOWN') throw new Error('expected TOP_DOWN scene');
    expect(scene.events).toHaveLength(0);
    expect(scene.tileLayers).toHaveLength(0);
    expect(project.items).toHaveLength(0);
    expect(project.variables).toHaveLength(0);
    expect(project.scenes.some((candidate) => candidate.type === 'DIALOGUE')).toBe(false);
  });

  it('gameId를 그대로 반영한다', () => {
    expect(createBlankProject(777).gameId).toBe(777);
  });
});
