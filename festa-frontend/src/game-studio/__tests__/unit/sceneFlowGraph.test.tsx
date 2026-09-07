// @vitest-environment jsdom
// 실험(정식 티켓 아님) — 상단 툴바 "🔀 게임 흐름" 버튼으로 씬 흐름 그래프 FloatingPanel을
// 여닫고, 노드 개수/START 표시가 맞는지, 노드를 클릭해도 창은 열린 채로 씬만 이동하는지
// (버튼을 다시 눌러야만 닫힘) 확인한다. FloatingPanel 자체의 드래그/리사이즈 동작은
// floatingPanel.test.tsx가 이미 검증하므로 여기서는 배선만 본다.
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
});

const GAME_ID = 700;

const setup = () => {
  // 처음 보는 gameId면 "처음 시작하기" 안내 모달이 같이 뜬다 — 이 테스트와 무관하므로
  // 이미 안내를 본 것으로 표시해 아예 안 뜨게 한다.
  window.localStorage.setItem(`festa.game-studio.onboarding.v3.${GAME_ID}`, 'done');
  const project = createStarterProject(GAME_ID);
  const { container } = render(
    <MemoryRouter>
      <GameStudioShell assetRepository={null} gameId={GAME_ID} initialProject={project} publisher={null} repository={null} />
    </MemoryRouter>,
  );
  return { container };
};

const panelHeadingText = (container: HTMLElement): string | null => (
  container.querySelector('.gss-panel-heading h2')?.textContent ?? null
);

const openFlowGraph = () => fireEvent.click(screen.getByRole('button', { name: '🔀 게임 흐름' }));

describe('GameStudioShell — 씬 흐름 그래프(실험)', () => {
  it('"🔀 게임 흐름" 버튼을 누르면 씬 개수만큼(+게임 완료 노드) 노드가 뜬다', () => {
    const { container } = setup();
    expect(container.querySelector('.gss-floating-panel')).toBeNull();

    openFlowGraph();

    expect(container.querySelector('.gss-floating-panel')).not.toBeNull();
    const nodes = container.querySelectorAll('.gss-flow-node');
    // library/librarianDialogue/ending 3개 + COMPLETE_GAME 액션이 있어 추가되는 완료 노드 1개.
    expect(nodes).toHaveLength(4);
    expect(container.querySelector('.gss-flow-node.is-start strong')?.textContent).toBe('도서관 입구');
    expect(container.querySelector('.gss-flow-node.is-endpoint strong')?.textContent).toBe('게임 완료');
  });

  it('노드를 클릭하면 그 씬으로 이동하지만, 창은 열린 채로 남는다', () => {
    const { container } = setup();
    openFlowGraph();

    // 씬 목록 행의 드래그 핸들도 "사서와 대화 순서 변경 핸들"처럼 이름이 같은 문자열로
    // 시작해 role/name 매칭이 여러 개 걸린다 — 그래프 노드(.gss-flow-node)만 콕 집는다.
    const targetNode = [...container.querySelectorAll('.gss-flow-node')]
      .find((node) => node.querySelector('strong')?.textContent === '사서와 대화');
    if (targetNode === undefined) throw new Error('expected the librarian dialogue flow node');
    fireEvent.click(targetNode);

    expect(container.querySelector('.gss-floating-panel')).not.toBeNull();
    expect(panelHeadingText(container)).toBe('사서와 대화');
  });

  it('"게임 흐름" 버튼을 다시 누르거나 닫기(×) 버튼을 누르면 닫힌다', () => {
    const { container } = setup();
    openFlowGraph();
    expect(container.querySelector('.gss-floating-panel')).not.toBeNull();

    openFlowGraph();
    expect(container.querySelector('.gss-floating-panel')).toBeNull();

    openFlowGraph();
    fireEvent.click(screen.getByRole('button', { name: '게임 흐름 닫기' }));
    expect(container.querySelector('.gss-floating-panel')).toBeNull();
  });
});
