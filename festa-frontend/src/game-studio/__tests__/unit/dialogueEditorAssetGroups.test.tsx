// @vitest-environment jsdom
// S15P21A604-570 — 대화 배경/인물 이미지 select에서 빌트인(제공 자료)과 내가 올린 이미지
// (내 자산)가 구분 없이 섞여 있던 문제(Notion QA #53)를 optgroup으로 고쳤다.
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DialogueEditor } from '../../studio/ui/DialogueEditor.tsx';
import { addAssetReference, addDialogueScene } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { parseGameProject, type DialogueScene, type GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 570;

const buildProject = (): { project: GameProject; sceneId: string } => {
  let project = addDialogueScene(parseGameProject(createStarterProject(GAME_ID)), 'OVERLAY');
  project = addAssetReference(project, {
    id: 'myBg',
    kind: 'IMAGE',
    source: `asset://local/${GAME_ID}/myBg`,
    label: 'sunset.png',
  });
  project = addAssetReference(project, {
    id: 'myPortrait',
    kind: 'IMAGE',
    source: `asset://local/${GAME_ID}/myPortrait`,
    label: 'friend.png',
  });
  const sceneId = project.scenes.at(-1)?.id;
  if (sceneId === undefined) throw new Error('expected a newly added DIALOGUE scene');
  return { project, sceneId };
};

const setup = () => {
  const { project, sceneId } = buildProject();
  const scene = project.scenes.find((candidate) => candidate.id === sceneId) as DialogueScene;
  const { container } = render(
    <DialogueEditor assetUrls={{}} onApply={() => undefined} project={project} scene={scene} />,
  );
  return { container };
};

describe('DialogueEditor — 배경·인물 select 자산 그룹(S15P21A604-570)', () => {
  it('연출 배경 select가 "제공 자료"/"내 자산" optgroup으로 나뉘고 원본 파일명이 보인다', () => {
    const { container } = setup();
    const selects = Array.from(container.querySelectorAll('select'));
    const backgroundSelect = selects.find((select) => select.parentElement?.textContent?.startsWith('연출 배경'));
    if (backgroundSelect === undefined) throw new Error('연출 배경 select not found');

    const groups = Array.from(backgroundSelect.querySelectorAll('optgroup'));
    const groupLabels = groups.map((group) => group.getAttribute('label'));
    expect(groupLabels).toContain('제공 자료');
    expect(groupLabels).toContain('내 자산');
    const customGroup = groups.find((group) => group.getAttribute('label') === '내 자산');
    expect(customGroup!.textContent).toContain('내 자산 · sunset.png');
  });

  it('인물 이미지/표정 select가 "제공 자료"/"내 자산" optgroup으로 나뉘고 원본 파일명이 보인다', () => {
    const { container } = setup();
    const selects = Array.from(container.querySelectorAll('select'));
    const portraitSelect = selects.find((select) => select.parentElement?.textContent?.startsWith('인물 이미지'));
    if (portraitSelect === undefined) throw new Error('인물 이미지 select not found');

    const groups = Array.from(portraitSelect.querySelectorAll('optgroup'));
    const groupLabels = groups.map((group) => group.getAttribute('label'));
    expect(groupLabels).toContain('제공 자료');
    expect(groupLabels).toContain('내 자산');
    const customGroup = groups.find((group) => group.getAttribute('label') === '내 자산');
    expect(customGroup!.textContent).toContain('내 자산 · friend.png');
  });
});
