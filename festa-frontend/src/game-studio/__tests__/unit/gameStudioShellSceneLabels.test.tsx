// @vitest-environment jsdom
// S15P21A604-488 — 씬 추가 버튼/기본 이름/타입 라벨을 기능 기준(TopDown/SideScroll,
// Overlay/Fullscreen)으로 바꾼 것을 실제 GameStudioShell을 마운트해서 확인한다. 이름 생성
// 로직 자체(카운터가 타입/presentation별로 독립적인지 등)는 authoringCommands.test.ts가
// 이미 검증하므로, 여기서는 그 결과가 화면 곳곳(버튼 라벨, 새로 추가된 씬의 헤딩,
// InspectorPanel 뱃지, 좌측 Scene 목록 행, 캔버스 툴바, 시작 템플릿 모달)에 그대로
// 반영되는지만 확인한다 — describeSceneType/describeSceneRuntimeMode(authoringRegistry.ts)
// 하나로 통일했으므로 한 곳이 어긋나면 나머지도 같이 깨져야 정상이다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const GAME_ID = 488;

const setup = () => {
  const project = createStarterProject(GAME_ID);
  const { container } = render(
    <MemoryRouter>
      <GameStudioShell assetRepository={null} gameId={GAME_ID} initialProject={project} publisher={null} repository={null} />
    </MemoryRouter>,
  );
  return { container };
};

// Scene 이름은 좌측 Scene 목록 행에도 나타나 getByText가 "여러 개 찾음" 오류를 낸다 —
// 헤딩(선택된 오브젝트가 없을 때만 보이는 Scene 개요, TOP_DOWN/PLATFORMER는
// InspectorPanel이, DIALOGUE는 GameStudioShell이 직접 그린다)만 콕 집어 확인한다.
const panelHeadingText = (container: HTMLElement): string | null => (
  container.querySelector('.gss-panel-heading h2')?.textContent ?? null
);

// .gss-type-badge는 캔버스 툴바에도 같은 클래스로 재사용된다(지금은 둘 다 같은 라벨을
// 보여주지만, InspectorPanel 헤딩 안에 있는 것만 콕 집어 캔버스 툴바 쪽과 섞이지 않게 한다).
const typeBadgeText = (container: HTMLElement): string | null => (
  container.querySelector('.gss-panel-heading .gss-type-badge')?.textContent ?? null
);

const canvasToolbarBadgeText = (container: HTMLElement): string | null => (
  container.querySelector('.gss-canvas-toolbar .gss-type-badge')?.textContent ?? null
);

const sceneRowSubtitles = (container: HTMLElement): readonly string[] => (
  [...container.querySelectorAll('.gss-scene-row small')].map((el) => el.textContent ?? '')
);

describe('GameStudioShell — 씬 추가 버튼/이름/타입 뱃지 명칭(S15P21A604-488)', () => {
  it('씬 추가 버튼 4개가 새 명칭으로 보인다', () => {
    setup();
    expect(screen.getByRole('button', { name: '+ 맵-TopDown' })).not.toBeNull();
    expect(screen.getByRole('button', { name: '+ 맵-SideScroll' })).not.toBeNull();
    expect(screen.getByRole('button', { name: '+ 대화-Overlay' })).not.toBeNull();
    expect(screen.getByRole('button', { name: '+ 대화-Fullscreen' })).not.toBeNull();
  });

  it('"+ 맵-TopDown"으로 추가한 씬은 새 이름 패턴을 쓰고, Inspector 타입 뱃지도 새 명칭이다', () => {
    const { container } = setup();
    // starter project가 이미 TOP_DOWN 씬을 하나("도서관 입구") 갖고 있으므로 새로 추가되는
    // 것은 2번이다.
    fireEvent.click(screen.getByRole('button', { name: '+ 맵-TopDown' }));

    expect(panelHeadingText(container)).toBe('새 맵-TopDown 2');
    expect(typeBadgeText(container)).toBe('맵-TopDown');
  });

  it('"+ 맵-SideScroll"로 추가한 씬은 새 이름 패턴을 쓰고, Inspector 타입 뱃지도 새 명칭이다', () => {
    const { container } = setup();
    fireEvent.click(screen.getByRole('button', { name: '+ 맵-SideScroll' }));

    expect(panelHeadingText(container)).toBe('새 맵-SideScroll 1');
    expect(typeBadgeText(container)).toBe('맵-SideScroll');
  });

  it('"+ 대화-Overlay"와 "+ 대화-Fullscreen"으로 추가한 씬은 새 이름 패턴을 쓴다(번호는 서로 독립)', () => {
    const { container } = setup();
    // starter project는 OVERLAY 1개("librarianDialogue")·FULL_SCREEN 1개("ending")를 이미
    // 갖고 있으므로 새로 추가되는 것은 각각 2번이어야 한다 — 카운터가 서로 독립적임을 화면
    // 레벨에서도 재확인한다(authoringCommands.test.ts는 모델 레벨에서 이미 확인함).
    fireEvent.click(screen.getByRole('button', { name: '+ 대화-Overlay' }));
    expect(panelHeadingText(container)).toBe('새 대화-Overlay 2');

    fireEvent.click(screen.getByRole('button', { name: '+ 대화-Fullscreen' }));
    expect(panelHeadingText(container)).toBe('새 대화-Fullscreen 2');
  });

  it('좌측 Scene 목록 행과 캔버스 툴바도 새 명칭을 쓴다', () => {
    const { container } = setup();
    // starter project의 첫 씬("도서관 입구")은 TOP_DOWN이고 기본으로 선택돼 있다.
    expect(sceneRowSubtitles(container)[0]).toBe('1 · 맵-TopDown');
    expect(canvasToolbarBadgeText(container)).toBe('맵-TopDown');

    fireEvent.click(screen.getByRole('button', { name: '+ 대화-Overlay' }));
    // 방금 추가한 대화 씬이 마지막 행이자 새로 선택된 씬이다.
    expect(sceneRowSubtitles(container).at(-1)).toBe(`${sceneRowSubtitles(container).length} · 대화-Overlay`);
  });

  it('시작 템플릿 모달도 새 명칭을 쓴다', () => {
    setup();
    fireEvent.click(screen.getByRole('button', { name: /Game Studio/ }));
    fireEvent.click(screen.getByRole('menuitem', { name: '시작 템플릿' }));

    // STORY 템플릿(TOP_DOWN)과 PLATFORMER 템플릿(SideScroll) 각각 하나씩 확인한다.
    expect(screen.getByText('스토리 어드벤처 · 맵-TopDown')).not.toBeNull();
    expect(screen.getByText('플랫폼 액션 · 맵-SideScroll')).not.toBeNull();
  });
});
