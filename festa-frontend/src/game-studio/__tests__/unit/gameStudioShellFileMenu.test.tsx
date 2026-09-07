// @vitest-environment jsdom
// S15P21A604-481 — 좌상단 "Game Studio" 라벨 팝업 메뉴(게임 초기화/시작 템플릿/JSON
// 가져오기·내보내기)의 배선을 검증한다. 실제 GameStudioShell을 마운트해서 확인한다 —
// 메뉴 항목 각각이 기존 로직(store.reset, downloadProject, 템플릿 모달)을 그대로 타는지는
// 이 UI 레이어에서만 드러나기 때문이다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { createBlankProject } from '../../studio/model/createBlankProject.ts';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const GAME_ID = 481;

const setup = () => {
  const project = createStarterProject(GAME_ID);
  const { container } = render(
    <MemoryRouter>
      <GameStudioShell assetRepository={null} gameId={GAME_ID} initialProject={project} publisher={null} repository={null} />
    </MemoryRouter>,
  );
  return { container };
};

const openFileMenu = () => fireEvent.click(screen.getByRole('button', { name: /Game Studio/ }));

// Scene 이름은 좌측 Scene 목록 행에도, InspectorPanel 헤딩에도 동시에 나타나 getByText가
// "여러 개 찾음" 오류를 낸다 — 헤딩(선택된 오브젝트가 없을 때만 보이는 Scene 개요)만 콕
// 집어 확인한다.
const panelHeadingText = (container: HTMLElement): string | null => (
  container.querySelector('.gss-panel-heading h2')?.textContent ?? null
);

describe('GameStudioShell — 좌상단 팝업 메뉴(S15P21A604-481)', () => {
  it('"Game Studio" 라벨을 누르면 4개 항목의 팝업 메뉴가 뜬다', () => {
    setup();
    expect(screen.queryByRole('menu')).toBeNull();

    openFileMenu();

    expect(screen.getByRole('menu')).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: '게임 초기화' })).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: '시작 템플릿' })).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'JSON 가져오기' })).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'JSON 내보내기' })).not.toBeNull();
  });

  it('메뉴 바깥(backdrop)을 누르면 닫힌다', () => {
    const { container } = setup();
    openFileMenu();
    expect(screen.queryByRole('menu')).not.toBeNull();

    const backdrop = container.querySelector('.gss-file-menu-backdrop');
    if (backdrop === null) throw new Error('expected backdrop element');
    fireEvent.mouseDown(backdrop);

    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('기존 헤더의 "시작 템플릿" 버튼과 사이드바 JSON 버튼은 더 이상 렌더되지 않는다', () => {
    const { container } = setup();
    expect(screen.queryByText('▦ 시작 템플릿')).toBeNull();
    expect(container.querySelector('.gss-import-export')).toBeNull();
  });

  it('"시작 템플릿" 메뉴 항목을 누르면 기존 템플릿 모달이 그대로 열린다(회귀 없음)', () => {
    setup();
    openFileMenu();

    fireEvent.click(screen.getByRole('menuitem', { name: '시작 템플릿' }));

    expect(screen.getByText('어떤 게임에서 시작할까요?')).not.toBeNull();
    // 메뉴 자신은 항목 클릭과 함께 닫혀 있어야 한다.
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('"게임 초기화" 클릭 → 확인 다이얼로그가 뜨고, 취소하면 프로젝트가 그대로다', () => {
    const { container } = setup();
    openFileMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: '게임 초기화' }));

    expect(screen.getByText('게임을 초기화할까요?')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.queryByText('게임을 초기화할까요?')).toBeNull();
    // 취소했으니 스타터 프로젝트의 원래 Scene 이름이 그대로 남아 있어야 한다.
    expect(panelHeadingText(container)).toBe('도서관 입구');
  });

  it('"게임 초기화" 확인 → 완전히 빈 프로젝트(Scene 1개)로 바뀌고 알림이 뜬다', () => {
    const { container } = setup();
    openFileMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: '게임 초기화' }));
    fireEvent.click(screen.getByRole('button', { name: '초기화' }));

    expect(screen.queryByText('게임을 초기화할까요?')).toBeNull();
    expect(screen.getByText('게임을 빈 프로젝트로 초기화했습니다. 저장 전 플레이 테스트를 권장합니다.')).not.toBeNull();
    // 초기화 후 새 Scene 이름("새 맵")이 InspectorPanel 헤딩에 반영돼 있어야 한다.
    expect(panelHeadingText(container)).toBe('새 맵');
  });

  // S15P21A604-481 — 실제로 발견된 회귀: "JSON 가져오기" 클릭이 (a) 메뉴를 닫는 상태
  // 갱신과 (b) fileInputRef.click()을 같은 이벤트에서 함께 실행하는데, 이전 구현은 hidden
  // <input type="file">을 {showFileMenu && (...)} 블록 안에 두고 있었다. 네이티브 파일
  // 선택창은 비동기(사용자가 실제로 고르기까지 시간이 걸림)라, 그 사이 (a)로 인한 리렌더가
  // 이미 그 input을 언마운트해 버린다 — 사용자가 파일을 골라도 change가 어떤 React
  // 컴포넌트에도 안 걸려 있어 onChange(importProject)가 조용히 안 불렸다(에러도 없이 아무
  // 효과도 없음). 그래서 "메뉴를 닫는 클릭 이후에도 input이 여전히 DOM에 있고 change가
  // 실제로 처리되는지"를 직접 검증한다 — jsdom의 동기 .click()만으로는 이 타이밍 버그가
  // 재현되지 않으므로, "메뉴 클릭 → (메뉴 닫힘 반영까지 기다림) → change 발화" 순서를 그대로
  // 흉내 낸다.
  it('"JSON 가져오기" 클릭으로 메뉴가 닫힌 뒤에도 파일 input이 여전히 동작한다(회귀 방어)', async () => {
    const { container } = setup();
    openFileMenu();

    fireEvent.click(screen.getByRole('menuitem', { name: 'JSON 가져오기' }));
    // 메뉴는 이미 닫혀 있다 — 실제 브라우저에서 네이티브 파일 선택창이 뜨는 동안의 상태다.
    expect(screen.queryByRole('menu')).toBeNull();

    const input = container.querySelector('input[type="file"]');
    if (input === null) throw new Error('메뉴가 닫힌 뒤 파일 input이 사라졌다 — 회귀');

    const imported = createBlankProject(GAME_ID);
    const file = new File([JSON.stringify(imported)], 'imported.json', { type: 'application/json' });
    Object.defineProperty(input, 'files', { configurable: true, value: [file] });
    fireEvent.change(input);

    // importProject가 실제로 끝까지 실행돼 project가 교체됐는지 확인한다.
    await screen.findByText('imported.json을 가져왔습니다. 저장 전 플레이 테스트를 권장합니다.');
    expect(panelHeadingText(container)).toBe('새 맵');
  });

  it('"JSON 내보내기" 메뉴 항목을 누르면 다운로드가 트리거되고 메뉴가 닫힌다', () => {
    const createObjectURL = vi.fn(() => 'blob:festa-test');
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }));
    setup();
    openFileMenu();

    fireEvent.click(screen.getByRole('menuitem', { name: 'JSON 내보내기' }));

    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();
    vi.unstubAllGlobals();
  });
});
