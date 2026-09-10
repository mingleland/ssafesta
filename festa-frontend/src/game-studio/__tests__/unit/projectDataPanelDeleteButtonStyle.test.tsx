// @vitest-environment jsdom
// S15P21A604-580 — variables/items/assets/목표 삭제 버튼이 스타일 없는 네이티브 "삭제"
// 텍스트 버튼이라 튀어 보이던 문제(Notion QA #55)를 빨간 × 아이콘(gss-delete-icon-button)
// 으로 통일했다. 유니코드 "×" 글자는 폰트마다 잉크가 박스 중앙에서 벗어나 보여서(아래·
// 왼쪽으로 치우침) SVG(DeleteIcon)로 바꿨다 — 텍스트가 아니라 svg 자식이 있는지 확인한다.
// assets 우측 "위" 정렬은 grid CSS 사안이라 유닛 테스트 대상이 아니다 — 클래스가 올바른
// 위치(1행)에 적용됐는지만 확인한다.
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ProjectDataPanel } from '../../studio/ui/ProjectDataPanel.tsx';
import { addAssetReference, replaceGameRules } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { DEFAULT_GAME_RULES, type GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 580;

const setup = (project: GameProject) => render(
  <ProjectDataPanel
    onApply={() => undefined}
    onDeleteAsset={() => undefined}
    onDeleteItem={() => undefined}
    onDeleteVariable={() => undefined}
    onUploadAsset={() => undefined}
    project={project}
  />,
);

const expectDeleteIconButton = (button: HTMLButtonElement) => {
  // 텍스트 글자가 아니라 SVG 아이콘이어야 한다(폰트 의존적인 글자 잉크 오프셋 회피).
  expect(button.textContent).toBe('');
  expect(button.querySelector('svg')).toBeTruthy();
  expect(button.classList.contains('gss-delete-icon-button')).toBe(true);
};

describe('ProjectDataPanel — 삭제 버튼 × 아이콘 통일(S15P21A604-580)', () => {
  it('변수 삭제 버튼이 SVG × 아이콘으로 표시되고 gss-delete-icon-button 클래스를 쓴다', () => {
    setup(createStarterProject(GAME_ID));
    const button = screen.getByRole('button', { name: 'doorOpened 삭제' }) as HTMLButtonElement;
    expectDeleteIconButton(button);
  });

  it('아이템 삭제 버튼이 SVG × 아이콘으로 표시되고 gss-delete-icon-button 클래스를 쓴다', () => {
    setup(createStarterProject(GAME_ID));
    const button = screen.getByRole('button', { name: 'libraryKey 삭제' }) as HTMLButtonElement;
    expectDeleteIconButton(button);
  });

  it('자산 삭제 버튼이 SVG × 아이콘으로 표시되고, gss-asset-list 안에서 1행(오른쪽 위)에 배치된다', () => {
    const project = addAssetReference(createStarterProject(GAME_ID), {
      id: 'myPortrait',
      kind: 'IMAGE',
      source: `asset://local/${GAME_ID}/myPortrait`,
      label: 'friend.png',
    });
    const { container } = setup(project);
    const button = screen.getByRole('button', { name: '내 자산 · friend.png 삭제' }) as HTMLButtonElement;
    expectDeleteIconButton(button);
    expect(container.querySelector('.gss-asset-list .gss-delete-icon-button')).toBe(button);
  });

  it('목표 삭제 버튼도 같은 SVG × 아이콘·클래스를 쓴다(기존에도 ×였음)', () => {
    let project = createStarterProject(GAME_ID);
    project = replaceGameRules(project, {
      ...(project.rules ?? DEFAULT_GAME_RULES),
      completion: { mode: 'ALL', objectives: [{ type: 'SCORE_AT_LEAST', target: 500 }] },
    });
    setup(project);
    const button = screen.getByRole('button', { name: '점수 달성 목표 삭제' }) as HTMLButtonElement;
    expectDeleteIconButton(button);
  });
});
