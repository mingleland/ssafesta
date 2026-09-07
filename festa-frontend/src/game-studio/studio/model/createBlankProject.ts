// S15P21A604-481 — "게임 초기화" 메뉴가 만드는 완전히 빈 프로젝트. Notion QA id=25에서 합의한
// 정의를 그대로 따른다: 씬 1개(빈 TOP_DOWN 맵, 배경/타일 없음) + 그 안에 PLAYER_SPAWN
// 오브젝트 1개(계약상 TOP_DOWN/PLATFORMER 씬은 PLAYER_SPAWN이 정확히 1개 있어야 하므로 이건
// "완전히 빈"이라도 생략할 수 없는 최소 요구사항이다 — 스프라이트 컴포넌트는 없어도 된다) +
// 빌트인 에셋 카탈로그는 그대로 유지(비우면 이후 오브젝트에 스프라이트를 붙일 후보 자체가
// 없어져 배치가 막힌다) + 그 외 오브젝트·이벤트·아이템·변수·타일·대화 씬은 전부 없음.
import type { GameProject } from '../../contracts/gameProject.ts';
import { BUILTIN_PROJECT_ASSETS } from '../assets/builtinAssetCatalog.ts';

export const BLANK_PROJECT_SCENE_ID = 'main';
export const BLANK_PROJECT_PLAYER_SPAWN_ID = 'playerSpawn';

export const createBlankProject = (gameId: number): GameProject => ({
  schemaVersion: '1.1.0',
  gameId,
  revision: 0,
  title: '새 게임',
  rules: {
    completion: { mode: 'ALL', objectives: [] },
    playerDefeat: 'RESPAWN',
  },
  startSceneId: BLANK_PROJECT_SCENE_ID,
  variables: [],
  items: [],
  assets: BUILTIN_PROJECT_ASSETS,
  scenes: [
    {
      id: BLANK_PROJECT_SCENE_ID,
      type: 'TOP_DOWN',
      name: '새 맵',
      width: 16,
      height: 10,
      tileLayers: [],
      objects: [
        {
          id: BLANK_PROJECT_PLAYER_SPAWN_ID,
          preset: 'PLAYER_SPAWN',
          position: { x: 8, y: 8 },
          visible: true,
          components: [],
        },
      ],
      events: [],
    },
  ],
});
