// @vitest-environment jsdom
// S15P21A604-540 — 플레이 화면 HUD(상태·목표·인벤토리·조작 안내)를 오른쪽 사이드바에서
// 게임 캔버스(.grp-map) 안쪽 오버레이로 재배치한다. 값 자체의 계산(objectiveProgress,
// playerHealth 등)은 이미 다른 테스트가 순수 함수 레벨에서 검증했으므로, 여기서는 그 값을
// 컴포넌트가 사이드바 없이 캔버스 안에 올바른 형태로 반영하는지만 본다.
import { act } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { createProjectFromTemplate } from '../../studio/model/projectTemplates.ts';
import { addTopDownScene } from '../../studio/model/authoringCommands.ts';
import { parseGameProject } from '../../contracts/gameProject.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
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

// starter project의 'library' Scene은 16×10, 스폰은 (8,8) — 스폰 바로 오른쪽(9,8)에 DAMAGE
// 함정을 놓아 ArrowRight 한 번으로 바로 밟게 한다(referenceGamePlayerDamagePopup.test.tsx와
// 동일 패턴 재사용).
const buildProjectWithHazard = (gameId: number, amount: number) => {
  const extended = addTopDownScene(createStarterProject(gameId));
  return parseGameProject({
    ...extended,
    scenes: extended.scenes.map((scene) => scene.id !== 'library' || scene.type !== 'TOP_DOWN' ? scene : {
      ...scene,
      objects: [...scene.objects, {
        id: 'entryHazard',
        preset: 'HAZARD' as const,
        position: { x: 9, y: 8 },
        visible: true,
        components: [{ type: 'DAMAGE' as const, amount }],
      }],
    }),
  });
};

const withObjective = (project: GameProject): GameProject => parseGameProject({
  ...project,
  rules: { completion: { mode: 'ALL' as const, objectives: [{ type: 'SCORE_AT_LEAST' as const, target: 100 }] }, playerDefeat: 'RESPAWN' as const },
});

describe('ReferenceGamePlayer — HUD 캔버스 오버레이 배선(S15P21A604-540)', () => {
  it('오른쪽 사이드바가 없고, 상태·인벤토리는 게임 캔버스(.grp-map) 안쪽에 있다', () => {
    const { container } = setup(createStarterProject(540));
    expect(container.querySelector('aside.grp-hud')).toBeNull();
    expect(container.querySelector('.grp-map .grp-hud-status')).not.toBeNull();
    expect(container.querySelector('.grp-map .grp-hud-inventory')).not.toBeNull();
  });

  it('게임 목표가 없으면 목표 바가 없고, 있으면 캔버스 상단에 뜬다', () => {
    const { container: withoutObjective } = setup(createStarterProject(540));
    expect(withoutObjective.querySelector('.grp-hud-objectives')).toBeNull();

    const { container: withGoal } = setup(withObjective(createStarterProject(541)));
    expect(withGoal.querySelector('.grp-map .grp-hud-objectives')).not.toBeNull();
  });

  it('하트는 maxPlayerHealth개가 전부 그려지고, 피격으로 체력이 깎이면 왼쪽부터 빈 하트로 바뀐다', () => {
    const project = buildProjectWithHazard(542, 1);
    const { container } = setup(project);
    const hearts = () => Array.from(container.querySelectorAll('.grp-hud-hearts span'));
    expect(hearts()).toHaveLength(3); // starter project는 maxPlayerHealth가 항상 3으로 고정된다.
    expect(hearts().every((heart) => heart.classList.contains('is-filled'))).toBe(true);

    act(() => { fireEvent.keyDown(window, { key: 'ArrowRight' }); });

    const afterHit = hearts();
    expect(afterHit).toHaveLength(3);
    expect(afterHit.filter((heart) => heart.classList.contains('is-filled'))).toHaveLength(2);
    expect(afterHit[0]!.classList.contains('is-filled')).toBe(false); // 왼쪽부터 사라진다.
  });

  it('점수는 "★ 숫자" 형식으로 캔버스 우측 상단(하트 아래)에 표시된다', () => {
    const { container } = setup(createStarterProject(543));
    expect(container.querySelector('.grp-hud-status .grp-hud-score')?.textContent).toBe('★ 0');
  });

  it('인벤토리는 좌측 상단 오버레이에 목록형으로 표시된다(빈 경우 "비어 있음")', () => {
    const { container } = setup(createStarterProject(544));
    expect(container.querySelector('.grp-hud-inventory small')?.textContent).toBe('비어 있음');
  });
});

describe('ReferenceGamePlayer — 조작 UI 단순화(S15P21A604-540)', () => {
  it('방향키 클릭 버튼이 없고, 키보드 이동은 그대로 동작한다(회귀 없음)', () => {
    const { container } = setup(createStarterProject(545));
    expect(container.querySelector('.grp-controls')).toBeNull();
    expect(screen.queryByRole('button', { name: '↑' })).toBeNull();

    act(() => { fireEvent.keyDown(window, { key: 'ArrowRight' }); fireEvent.keyUp(window, { key: 'ArrowRight' }); });
    const player = container.querySelector('.grp-player') as HTMLElement;
    // starter project 스폰 (8,8), Scene 폭 16 → RIGHT 1회 후 x=9.
    expect(player.style.left).toBe(`${((9 + .5) / 16) * 100}%`);
  });

  it('E는 클릭해도 반응 없는 순수 안내 라벨이고, 키보드 상호작용은 그대로 동작한다', () => {
    const { container } = setup(createStarterProject(546));
    expect(screen.queryByRole('button', { name: /상호작용/ })).toBeNull();
    expect(screen.getByText('E 상호작용')).not.toBeNull();

    // 클릭 가능한 button이 아님을 위에서 이미 role 부재로 확인했다 — 클릭해도 예외 없이
    // 무반응이어야 한다(별도 onClick이 없으므로 이벤트만 쏴서 에러가 안 나는지 확인).
    expect(() => fireEvent.click(container.querySelector('.grp-hud-actions span')!)).not.toThrow();
  });

  it('발사가 없는 씬에서는 F 라벨이 없고, 발사가 있는 씬에서는 E 옆에 F도 뜬다', () => {
    const { container: noShoot } = setup(createStarterProject(547));
    expect(noShoot.querySelector('.grp-hud-actions')?.textContent).not.toMatch(/F 발사/);

    const shooterProject = createProjectFromTemplate(548, 'SHOOTER');
    const { container: withShoot } = setup(shooterProject);
    const actions = withShoot.querySelector('.grp-hud-actions')?.textContent ?? '';
    expect(actions).toContain('E 상호작용');
    expect(actions).toContain('F 발사');
    expect(screen.queryByRole('button', { name: /발사/ })).toBeNull();
  });
});
