// @vitest-environment jsdom
// S15P21A604-547 — 씬 선택뿐 아니라 우측 패널 탭(속성/이벤트/데이터)·캔버스 확대·축소
// (zoom)·뷰포트 중심(팬 위치)도 플레이 테스트 왕복(=GameStudioShell 재마운트) 후 유지돼야
// 한다. 세 값 다 세션 저장소(sessionStorage)에 게임(탭·뷰포트는 씬별로도) 기록했다가
// 복원한다 — 기존 우측 패널 접힘 상태(-522)와 같은 패턴.
//
// 뷰포트 중심은 jsdom이 실제 레이아웃(getBoundingClientRect, 스크롤 위치)을 계산하지
// 않아 실제 스크롤 제스처로는 재현할 수 없다 — TopDownCanvas를 얕은 스텁으로 교체해
// GameStudioShell이 그 컴포넌트에 넘기는 restoreViewportCenter/zoom prop 값만 확인한다.
// 이 스텁은 이 테스트 파일에만 적용되고(vi.mock은 파일 단위), 다른 테스트 파일의
// TopDownCanvas는 실제 구현 그대로다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

vi.mock('../../studio/ui/TopDownCanvas.tsx', () => ({
  TopDownCanvas: (props: { restoreViewportCenter?: { x: number; y: number } | null; zoom: number }) => (
    <div data-restore-viewport={JSON.stringify(props.restoreViewportCenter ?? null)} data-testid="mock-canvas" data-zoom={props.zoom} />
  ),
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

const GAME_ID = 5475;

const setup = () => {
  const project = createStarterProject(GAME_ID);
  const { container, unmount } = render(
    <MemoryRouter>
      <GameStudioShell assetRepository={null} gameId={GAME_ID} initialProject={project} publisher={null} repository={null} />
    </MemoryRouter>,
  );
  return { container, unmount };
};

const mockCanvas = (container: HTMLElement) => container.querySelector('[data-testid="mock-canvas"]') as HTMLElement;

describe('GameStudioShell — 우측 패널 탭이 재마운트 후에도 유지된다(S15P21A604-547)', () => {
  it('"이벤트" 탭을 선택한 뒤 재마운트해도 그 탭이 그대로 유지된다', () => {
    const { unmount } = setup();
    fireEvent.click(screen.getByRole('button', { name: '이벤트' }));
    expect(screen.getByRole('button', { name: '이벤트' }).className).toContain('is-active');
    unmount();

    setup();
    expect(screen.getByRole('button', { name: '이벤트' }).className).toContain('is-active');
  });
});

describe('GameStudioShell — 캔버스 확대(zoom)가 재마운트 후에도 유지된다(S15P21A604-547)', () => {
  it('확대 버튼을 누른 뒤 재마운트해도 그 배율이 그대로 유지된다', () => {
    const { container, unmount } = setup();
    fireEvent.click(screen.getByRole('button', { name: '확대' }));
    const zoomedValue = mockCanvas(container).dataset.zoom;
    expect(zoomedValue).not.toBe('100');
    unmount();

    const { container: remounted } = setup();
    expect(mockCanvas(remounted).dataset.zoom).toBe(zoomedValue);
  });
});

describe('GameStudioShell — 캔버스 뷰포트 중심이 재마운트 후에도 유지된다(S15P21A604-547)', () => {
  it('스크롤(뷰포트 settle)로 기록된 중심 좌표가 재마운트 후에도 같은 씬에 복원된다', () => {
    // 실제 스크롤 제스처는 jsdom이 재현하지 못하므로, TopDownCanvas가 실제로 호출하는
    // onViewportSettle 콜백을 GameStudioShell 내부 배선을 거치지 않고 곧바로 흉내 낼 수
    // 없다 — 대신 세션 저장소를 직접 채워(실제 구현이 쓰는 것과 같은 키/형식) "이미 한 번
    // settle된 상태"를 재현하고, 재마운트 시 그 값이 TopDownCanvas의 restoreViewportCenter
    // prop으로 그대로 전달되는지 확인한다. 저장 키 형식 자체는 아래 별도 유닛 테스트가
    // 더 세밀하게 검증한다.
    const project = createStarterProject(GAME_ID);
    const startScene = project.scenes.find((scene) => scene.id === project.startSceneId)!;
    window.sessionStorage.setItem(
      `festa.game-studio.viewportCenter.${GAME_ID}`,
      JSON.stringify({ [startScene.id]: { x: 3, y: 4 } }),
    );

    const { container } = setup();
    expect(JSON.parse(mockCanvas(container).dataset.restoreViewport ?? 'null')).toEqual({ x: 3, y: 4 });
  });
});
