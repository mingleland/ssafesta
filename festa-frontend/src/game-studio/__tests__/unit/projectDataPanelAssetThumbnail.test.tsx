// @vitest-environment jsdom
// S15P21A604-573 — 데이터 탭 "내 자산" 목록이 종류 배지(IMAGE/TILESET/AUDIO)로만 뭉뚱그려
// 보이고 실제 업로드한 이미지는 전혀 렌더하지 않던 문제(Notion QA #54)를 고쳤다. 이미 계산돼
// 있던 assetUrls를 이 패널에도 흘려보내고, gss-builtin-library와 같은 패턴으로 썸네일을 그린다.
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ProjectDataPanel } from '../../studio/ui/ProjectDataPanel.tsx';
import { addAssetReference } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 573;

const setup = (project: GameProject, assetUrls?: Readonly<Record<string, string>>) => render(
  <ProjectDataPanel
    assetUrls={assetUrls}
    onApply={() => undefined}
    onDeleteAsset={() => undefined}
    onDeleteItem={() => undefined}
    onDeleteVariable={() => undefined}
    onUploadAsset={() => undefined}
    project={project}
  />,
);

const findRow = (container: HTMLElement, assetId: string): HTMLElement => {
  const row = Array.from(container.querySelectorAll('.gss-asset-list > div'))
    .find((candidate) => candidate.textContent?.includes(assetId));
  if (row === undefined) throw new Error(`asset row not found: ${assetId}`);
  return row as HTMLElement;
};

describe('ProjectDataPanel — 자산 목록 썸네일(S15P21A604-573)', () => {
  it('assetUrls에 해석된 IMAGE 자산은 실제 이미지 썸네일을 보여준다', () => {
    const project = addAssetReference(createStarterProject(GAME_ID), {
      id: 'myPortrait',
      kind: 'IMAGE',
      source: `asset://local/${GAME_ID}/myPortrait`,
      label: 'friend.png',
    });
    const { container } = setup(project, { myPortrait: 'blob:mock-portrait' });

    const row = findRow(container, 'myPortrait');
    const thumb = row.querySelector('.gss-asset-thumb') as HTMLElement | null;
    expect(thumb).toBeTruthy();
    expect(thumb!.style.backgroundImage).toContain('blob:mock-portrait');
  });

  it('assetUrls에 아직 없으면(로딩 중) 썸네일 없이 배지만 남고 레이아웃이 깨지지 않는다', () => {
    const project = addAssetReference(createStarterProject(GAME_ID), {
      id: 'myPortrait',
      kind: 'IMAGE',
      source: `asset://local/${GAME_ID}/myPortrait`,
      label: 'friend.png',
    });
    const { container } = setup(project, {});

    const row = findRow(container, 'myPortrait');
    expect(row.querySelector('.gss-asset-thumb')).toBeNull();
    expect(row.textContent).toContain('IMAGE');
  });

  it('assetUrls prop을 아예 안 넘겨도(기존 호출부·테스트) 크래시하지 않는다', () => {
    const project = createStarterProject(GAME_ID);
    const { container } = setup(project);
    expect(container.querySelector('.gss-asset-list')).toBeTruthy();
  });

  it('TILESET 자산은 resolveTilesetVisual로 실제 썸네일을 보여준다', () => {
    const project = addAssetReference(createStarterProject(GAME_ID), {
      id: 'myTileset',
      kind: 'TILESET',
      source: `asset://local/${GAME_ID}/myTileset`,
      label: 'dungeon.png',
    });
    const { container } = setup(project, { myTileset: 'blob:mock-tileset' });

    const row = findRow(container, 'myTileset');
    const thumb = row.querySelector('.gss-asset-thumb') as HTMLElement | null;
    expect(thumb).toBeTruthy();
    expect(thumb!.style.backgroundImage).toContain('blob:mock-tileset');
  });

  it('AUDIO 자산은 썸네일 없이 기존처럼 배지만 표시된다(회귀 없음)', () => {
    const project = addAssetReference(createStarterProject(GAME_ID), {
      id: 'myAudio',
      kind: 'AUDIO',
      source: `asset://local/${GAME_ID}/myAudio`,
      label: 'bgm.mp3',
    });
    const { container } = setup(project, { myAudio: 'blob:mock-audio' });

    const row = findRow(container, 'myAudio');
    expect(row.querySelector('.gss-asset-thumb')).toBeNull();
    expect(row.textContent).toContain('AUDIO');
  });
});
