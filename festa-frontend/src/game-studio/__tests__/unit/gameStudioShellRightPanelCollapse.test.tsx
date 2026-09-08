// @vitest-environment jsdom
// S15P21A604-522 — 대화 씬 편집 시 유용한 정보가 거의 없는 우측 속성/이벤트/데이터 패널을
// 접었다 펼 수 있게 한다. 맵 타입(TOP_DOWN/PLATFORMER) 씬은 기본 펼침, 대화 타입(DIALOGUE)
// 씬은 기본 접힘이며, 씬별 최신 상태를 기억한다(브라우저 세션 한정 — sessionStorage. 기존
// panelWidths처럼 localStorage에 게임과 무관하게 영구 저장하는 것과는 의도적으로 다르다).
// 구분선(.gss-panel-divider)이 이미 position:relative라 그 위에 얹은 토글 버튼은 폭 드래그
// 시 자동으로 같이 움직인다(별도 좌표 계산이 필요 없어 여기서 따로 검증하지 않는다) —
// 여기서는 접기/펼치기 상태 배선과 드래그 무시만 확인한다.
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

beforeAll(() => {
  if (typeof HTMLElement.prototype.setPointerCapture !== 'function') {
    HTMLElement.prototype.setPointerCapture = () => undefined;
    HTMLElement.prototype.releasePointerCapture = () => undefined;
    HTMLElement.prototype.hasPointerCapture = () => false;
  }
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.sessionStorage.clear();
});

const GAME_ID = 522;

// starter project(createStarterProject.ts) 씬 구성: '도서관 입구'(TOP_DOWN, 시작 씬),
// '사서와 대화'(DIALOGUE·OVERLAY), '도서관 밖으로'(DIALOGUE·FULL_SCREEN).
const MAP_SCENE_NAME = '도서관 입구';
const DIALOGUE_SCENE_NAME = '사서와 대화';
const OTHER_DIALOGUE_SCENE_NAME = '도서관 밖으로';

const setup = () => {
  const project = createStarterProject(GAME_ID);
  const { container } = render(
    <MemoryRouter>
      <GameStudioShell assetRepository={null} gameId={GAME_ID} initialProject={project} publisher={null} repository={null} />
    </MemoryRouter>,
  );
  return { container };
};

// 씬 이름이 여러 군데(좌측 목록 등)에 나타날 수 있어 .gss-scene-row 안의 strong 텍스트로
// 콕 집어 찾는다(gameStudioShellSceneLabels.test.tsx와 동일한 접근).
const clickScene = (container: HTMLElement, name: string) => {
  const row = [...container.querySelectorAll('.gss-scene-row')]
    .find((candidate) => candidate.querySelector('strong')?.textContent === name);
  if (row === undefined) throw new Error(`scene row not found: ${name}`);
  // 행 안에는 버튼이 두 개다 — 순서 변경용 드래그 핸들(☰, .gss-drag-handle)이 먼저 오고
  // 그 다음이 실제 선택 버튼이라, 첫 querySelector('button')만 쓰면 핸들을 잘못 클릭한다.
  fireEvent.click(row.querySelector('button:not(.gss-drag-handle)') as HTMLButtonElement);
};

const isRightPanelExpanded = (container: HTMLElement): boolean => (
  container.querySelector('.gss-panel-tabs') !== null
);

const rightDivider = (container: HTMLElement): HTMLElement => (
  container.querySelector('.gss-panel-divider--collapsible') as HTMLElement
);

const collapseToggleButton = (container: HTMLElement): HTMLButtonElement => (
  rightDivider(container).querySelector('button') as HTMLButtonElement
);

describe('GameStudioShell — 속성/이벤트/데이터 패널 접기(S15P21A604-522)', () => {
  it('맵 타입 씬은 우측 패널이 기본으로 펼쳐져 있다', () => {
    const { container } = setup();
    expect(isRightPanelExpanded(container)).toBe(true);
  });

  it('대화 타입 씬으로 이동하면 우측 패널이 기본으로 접힌다', () => {
    const { container } = setup();
    clickScene(container, DIALOGUE_SCENE_NAME);
    expect(isRightPanelExpanded(container)).toBe(false);
  });

  it('새로 추가한 씬도 타입 기본값을 즉시 따른다', () => {
    const { container } = setup();
    fireEvent.click(screen.getByRole('button', { name: '+ 대화-Overlay' }));
    expect(isRightPanelExpanded(container)).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '+ 맵-TopDown' }));
    expect(isRightPanelExpanded(container)).toBe(true);
  });

  it('토글 버튼으로 접고 펼 수 있고, 씬을 옮겼다 돌아와도 마지막 상태가 유지된다', () => {
    const { container } = setup();
    clickScene(container, DIALOGUE_SCENE_NAME);
    expect(isRightPanelExpanded(container)).toBe(false);

    fireEvent.click(collapseToggleButton(container));
    expect(isRightPanelExpanded(container)).toBe(true);

    // 다른 대화 씬으로 옮기면 그 씬은 아직 저장된 상태가 없으니 타입 기본값(접힘)을 따른다.
    clickScene(container, OTHER_DIALOGUE_SCENE_NAME);
    expect(isRightPanelExpanded(container)).toBe(false);

    // 원래 씬으로 돌아오면 방금 펼쳐뒀던 상태가 그대로 복원된다.
    clickScene(container, DIALOGUE_SCENE_NAME);
    expect(isRightPanelExpanded(container)).toBe(true);
  });

  it('접힌 상태에서 구분선을 드래그해도 폭이 바뀌지 않는다(클릭으로만 펼침)', () => {
    // 접힌 동안은 --gss-right-width가 항상 0px로 강제 표시되므로, 드래그가 실제로
    // 무시됐는지는 "드래그 시도 후 다시 펼쳤을 때 원래 저장돼 있던 폭 그대로인지"로
    // 확인해야 한다 — 펼쳐진 채로 확인하면 0px 강제 표시에 가려 드래그가 실제로
    // panelWidths.right를 바꿨어도 통과해 버려서 이 회귀를 못 잡는다.
    const { container } = setup();
    const layout = container.querySelector('.gss-layout') as HTMLElement;
    const widthBefore = layout.style.getPropertyValue('--gss-right-width');

    clickScene(container, DIALOGUE_SCENE_NAME);
    expect(isRightPanelExpanded(container)).toBe(false);
    expect(layout.style.getPropertyValue('--gss-right-width')).toBe('0px');

    const divider = rightDivider(container);
    fireEvent.pointerDown(divider, { clientX: 800, pointerId: 1 });
    fireEvent.pointerMove(divider, { clientX: 600, pointerId: 1 });
    fireEvent.pointerUp(divider, { clientX: 600, pointerId: 1 });

    fireEvent.click(collapseToggleButton(container));
    expect(isRightPanelExpanded(container)).toBe(true);
    expect(layout.style.getPropertyValue('--gss-right-width')).toBe(widthBefore);
  });

  it('접힌 상태에서도 접기/펼치기 버튼은 항상 보이고 클릭할 수 있다', () => {
    const { container } = setup();
    clickScene(container, DIALOGUE_SCENE_NAME);
    const button = collapseToggleButton(container);
    expect(button).not.toBeNull();
    expect(button.getAttribute('aria-label')).toBe('속성/이벤트/데이터 패널 펼치기');
    fireEvent.click(button);
    expect(collapseToggleButton(container).getAttribute('aria-label')).toBe('속성/이벤트/데이터 패널 접기');
  });
});
