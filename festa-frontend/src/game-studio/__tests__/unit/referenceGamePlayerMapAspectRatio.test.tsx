// @vitest-environment jsdom
// S15P21A604-492 — .grp-map이 width: 100%(고정값) + height: auto + aspect-ratio +
// max-height 조합이라, 세로가 긴 씬은 aspect-ratio로 계산된 높이가 max-height를 넘어
// 잘리는데 CSS 스펙상 폭은 재계산되지 않아 비율이 깨졌다(세로로 길게 만들어도 항상
// 가로로 넓은 원래 비율처럼 보임 — Notion QA id=29). jsdom은 실제 레이아웃 계산(calc,
// aspect-ratio 렌더링)을 하지 않으므로, 여기서는 "생성된 인라인 style.width 값 자체가
// 고정 100%가 아니라 씬 비율을 반영한 계산식인지"만 검증한다 — 실제 화면 비율은 로컬
// 브라우저에서 육안으로 확인한다.
// S15P21A604-542 — 가로 상한이 고정 1120px에서 화면 기준(100vw - 68px)의 92%로 바뀌었다.
// 세로 쪽((100vh - 130px) * 비율)에는 .92를 곱하지 않는다 — 그 값 자체가 이미 "넘치지
// 않는 최대치"였어서 다시 92%로 줄이면 세로 제한이 걸리는 씬은 오히려 이전보다 작아지는
// 회귀가 났다(육안 확인으로 발견, 기존 클린한 0.2/5 배수는 그대로 유지된다).
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { createBlankProject } from '../../studio/model/createBlankProject.ts';
import { parseGameProject, type GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 492;

const buildProject = (width: number, height: number): GameProject => {
  const base = createBlankProject(GAME_ID);
  return parseGameProject({
    ...base,
    scenes: base.scenes.map((scene) => (
      scene.type === 'TOP_DOWN'
        ? {
            ...scene,
            width,
            height,
            // 기본 PLAYER_SPAWN 위치(8,8)가 축소된 크기 밖으로 나갈 수 있어 중앙으로 재배치한다.
            objects: scene.objects.map((object) => (
              object.preset === 'PLAYER_SPAWN'
                ? { ...object, position: { x: Math.floor(width / 2), y: Math.floor(height / 2) } }
                : object
            )),
          }
        : scene
    )),
  });
};

const renderMap = (width: number, height: number) => {
  const { container } = render(
    <ReferenceGamePlayer
      mode="PREVIEW"
      onExit={() => undefined}
      project={buildProject(width, height)}
      sessionPort={createPreviewGameSessionPort()}
    />,
  );
  return container.querySelector('.grp-map') as HTMLElement;
};

describe('ReferenceGamePlayer — 플레이 화면 맵 비율(S15P21A604-492)', () => {
  it('세로가 긴 씬(8×40)은 폭이 고정 100%가 아니라 화면 기준 상한을 반영한 계산식이다', () => {
    const map = renderMap(8, 40);

    // 가로 상한(화면의 92%)과, 세로 상한을 씬 비율(8/40=0.2)로 역산한 값 중 작은 쪽이어야
    // 한다.
    expect(map.style.width).not.toBe('100%');
    expect(map.style.width).toBe('min(0.92 * (100vw - 68px), 0.2 * (100vh - 130px))');
  });

  it('가로가 긴 씬(40×8)은 세로가 긴 씬과 정확히 역수 관계의 계산식을 쓴다(회귀 없이 동일한 로직 경로)', () => {
    const map = renderMap(40, 8);

    expect(map.style.width).not.toBe('100%');
    expect(map.style.width).toBe('min(0.92 * (100vw - 68px), 5 * (100vh - 130px))');
  });

  it('aspect-ratio는 기존처럼 씬 비율 그대로 유지된다(회귀 없음)', () => {
    const map = renderMap(8, 40);
    expect(map.style.aspectRatio).toBe('8 / 40');
  });
});
