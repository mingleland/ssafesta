// @vitest-environment jsdom
// 실험(정식 티켓 아님) — 상단 툴바 "🔀 게임 흐름" 버튼으로 씬 흐름 그래프 모달을 여닫고,
// 노드 개수/START 표시가 맞는지, 노드를 클릭하면 그 씬으로 이동하며 모달이 닫히는지 확인한다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const GAME_ID = 700;

const setup = () => {
  // 처음 보는 gameId면 "처음 시작하기" 안내 모달이 같이 뜨는데, 그것도 같은
  // .gss-guide-backdrop 클래스를 써서 배경 클릭 테스트가 엉뚱한 모달을 닫아버린다 —
  // 이미 안내를 본 것으로 표시해 이 테스트와 무관한 모달이 안 뜨게 한다.
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

describe('GameStudioShell — 씬 흐름 그래프(실험)', () => {
  it('"🔀 게임 흐름" 버튼을 누르면 씬 개수만큼(+게임 완료 노드) 노드가 뜬다', () => {
    const { container } = setup();
    expect(screen.queryByRole('dialog', { name: /게임 흐름/ })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '🔀 게임 흐름' }));

    expect(container.querySelector('.gss-flow-graph-modal')).not.toBeNull();
    const nodes = container.querySelectorAll('.gss-flow-node');
    // library/librarianDialogue/ending 3개 + COMPLETE_GAME 액션이 있어 추가되는 완료 노드 1개.
    expect(nodes).toHaveLength(4);
    expect(container.querySelector('.gss-flow-node.is-start strong')?.textContent).toBe('도서관 입구');
    expect(container.querySelector('.gss-flow-node.is-endpoint strong')?.textContent).toBe('게임 완료');
  });

  it('노드를 클릭하면 그 씬으로 이동하고 모달이 닫힌다', () => {
    const { container } = setup();
    fireEvent.click(screen.getByRole('button', { name: '🔀 게임 흐름' }));

    // 씬 목록 행의 드래그 핸들도 "사서와 대화 순서 변경 핸들"처럼 이름이 같은 문자열로
    // 시작해 role/name 매칭이 여러 개 걸린다 — 그래프 노드(.gss-flow-node)만 콕 집는다.
    const targetNode = [...container.querySelectorAll('.gss-flow-node')]
      .find((node) => node.querySelector('strong')?.textContent === '사서와 대화');
    if (targetNode === undefined) throw new Error('expected the librarian dialogue flow node');
    fireEvent.click(targetNode);

    expect(container.querySelector('.gss-flow-graph-modal')).toBeNull();
    expect(panelHeadingText(container)).toBe('사서와 대화');
  });

  it('배경(backdrop) 클릭이나 닫기(×) 버튼으로 닫을 수 있다', () => {
    const { container } = setup();
    fireEvent.click(screen.getByRole('button', { name: '🔀 게임 흐름' }));
    fireEvent.click(screen.getByRole('button', { name: '게임 흐름 닫기' }));
    expect(container.querySelector('.gss-flow-graph-modal')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '🔀 게임 흐름' }));
    const backdrop = container.querySelector('.gss-guide-backdrop');
    if (backdrop === null) throw new Error('expected backdrop element');
    fireEvent.mouseDown(backdrop);
    expect(container.querySelector('.gss-flow-graph-modal')).toBeNull();
  });
});
