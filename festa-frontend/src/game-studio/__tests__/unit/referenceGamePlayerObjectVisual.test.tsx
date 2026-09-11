// @vitest-environment jsdom
// S15P21A604-535 — 이미지 asset이 있는 오브젝트는 어두운 배지 박스 없이(has-visual),
// 이미지가 없는(이모지 폴백) 오브젝트는 기존 배지 스타일이 유지되는지 확인한다. 실제
// 배경색/테두리 렌더링(computed style)까지는 jsdom에서 신뢰성 있게 확인하기 어려워
// 여기서는 "배선"인 has-visual 클래스 부여 여부까지만 본다 — 실제 룩은 수동 확인으로
// 커버한다.
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { parseGameProject } from '../../contracts/gameProject.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const STABLE_ASSET_URLS = {};

const setup = (project: GameProject) => render(
  <ReferenceGamePlayer
    assetUrls={STABLE_ASSET_URLS}
    mode="PREVIEW"
    onExit={() => undefined}
    project={project}
    sessionPort={createPreviewGameSessionPort()}
  />,
);

describe('ReferenceGamePlayer — 오브젝트 이미지 배경 투명화(S15P21A604-535)', () => {
  it('SPRITE 컴포넌트(이미지 asset)가 있는 오브젝트는 has-visual 클래스가 붙는다', () => {
    // starter project의 librarian은 SPRITE(npcImage, builtin 이미지) + INTERACTABLE을
    // 갖고 있다 — builtin 이미지는 assetUrls 없이도(findBuiltinStaticImage) 해석된다.
    const { container } = setup(createStarterProject(535));
    const librarian = container.querySelector('.grp-object--npc');
    expect(librarian).not.toBeNull();
    expect(librarian?.classList.contains('has-visual')).toBe(true);
  });

  it('SPRITE 컴포넌트가 없어 이모지 폴백만 뜨는 오브젝트는 has-visual 클래스가 붙지 않는다', () => {
    const original = createStarterProject(535);
    const project = parseGameProject({
      ...original,
      scenes: original.scenes.map((scene) => scene.id !== 'library' || scene.type !== 'TOP_DOWN' ? scene : {
        ...scene,
        objects: [...scene.objects, {
          id: 'noImageDecoration',
          preset: 'DECORATION' as const,
          position: { x: 3, y: 3 },
          visible: true,
          components: [],
        }],
      }),
    });
    const { container } = setup(project);
    const decoration = container.querySelector('.grp-object--decoration');
    expect(decoration).not.toBeNull();
    expect(decoration?.classList.contains('has-visual')).toBe(false);
  });
});
