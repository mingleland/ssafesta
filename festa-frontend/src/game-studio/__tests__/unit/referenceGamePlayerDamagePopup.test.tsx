// @vitest-environment jsdom
// S15P21A604-526 — 체력이 깎일 때 캐릭터 위에 "-N" 표시가 뜨고, 일정 시간 뒤 자동으로
// 사라지는지 컴포넌트 레벨에서 확인한다. 신호 자체(lastDamageAmount/lastDamageTick가
// 무적 시간 중에는 갱신되지 않는 것 등)는 referenceRuntimeDamageSignal.test.ts가 이미
// 순수 함수 레벨에서 검증했으므로, 여기서는 "그 신호를 컴포넌트가 실제로 화면에
// 반영하는가"만 본다.
import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { addTopDownScene } from '../../studio/model/authoringCommands.ts';
import { parseGameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

beforeEach(() => {
  vi.useFakeTimers();
});

const STABLE_ASSET_URLS = {};

// starter project의 'library' Scene은 16×10, 스폰은 (8,8) — 스폰 바로 오른쪽(9,8)에
// DAMAGE 함정을 놓아 ArrowRight 한 번으로 바로 밟게 한다.
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

const setup = (project: ReturnType<typeof buildProjectWithHazard>) => {
  const { container } = render(
    <ReferenceGamePlayer
      assetUrls={STABLE_ASSET_URLS}
      mode="PREVIEW"
      onExit={() => undefined}
      project={project}
      sessionPort={createPreviewGameSessionPort()}
    />,
  );
  return { container };
};

describe('ReferenceGamePlayer — 체력 깎이는 표시(S15P21A604-526)', () => {
  it('함정에 맞으면 캐릭터 위에 "-N" 표시가 뜬다', () => {
    const project = buildProjectWithHazard(526, 2);
    const { container } = setup(project);
    expect(container.querySelector('.grp-damage-popup')).toBeNull();

    act(() => { fireEvent.keyDown(window, { key: 'ArrowRight' }); });

    expect(container.querySelector('.grp-damage-popup')?.textContent).toBe('-2');
  });

  it('표시는 애니메이션 지속시간이 지나면 화면에서 사라진다', () => {
    const project = buildProjectWithHazard(526, 1);
    const { container } = setup(project);

    act(() => { fireEvent.keyDown(window, { key: 'ArrowRight' }); });
    expect(container.querySelector('.grp-damage-popup')).not.toBeNull();

    act(() => { vi.advanceTimersByTime(900); });
    expect(container.querySelector('.grp-damage-popup')).toBeNull();
  });

  it('무적 시간 동안은 계속 접촉 중이어도 표시가 다시 뜨지 않고, 끝나면 다시 뜬다', () => {
    // 탭 이동만으로는 tickCount가 전혀 흐르지 않아(월드 tick은 120ms 간격 setInterval
    // 에서만 증가) "제자리에서 왔다갔다"로는 진짜 무적 시간(tick 기준)을 검증할 수
    // 없었다 — tickReferenceWorld는 매 tick마다 플레이어가 서 있는 칸의 DAMAGE 컴포넌트를
    // 다시 적용하므로, 함정 위에 계속 서 있게 한 뒤 fake timer로 tick을 직접 흘려보내야
    // 진짜 무적 시간 경계를 넘나들 수 있다.
    const project = buildProjectWithHazard(526, 1);
    const { container } = setup(project);

    // 키를 누른 채로 두면(keyUp 없이) 이후 매 tick마다 movePlayerFromHeldKeys가 계속
    // 오른쪽으로 밀어 함정을 그냥 지나쳐 버린다 — 반드시 keyUp까지 해서 제자리에 멈춰야
    // "계속 접촉 중"이라는 이 테스트의 전제가 성립한다.
    act(() => { fireEvent.keyDown(window, { key: 'ArrowRight' }); fireEvent.keyUp(window, { key: 'ArrowRight' }); }); // 함정 진입, tick 0에서 1회 피격 → invulnerableUntilTick = 8
    expect(container.querySelectorAll('.grp-damage-popup')).toHaveLength(1);

    act(() => { vi.advanceTimersByTime(120 * 3); }); // tick 3까지 진행 — 아직 무적(3 < 8), 표시도 아직 안 사라짐(900ms 전)
    expect(container.querySelectorAll('.grp-damage-popup')).toHaveLength(1);

    act(() => { vi.advanceTimersByTime(540); }); // 누적 900ms — 표시 자체는 지속시간이 끝나 사라지지만, tick은 아직 7이라 계속 무적
    expect(container.querySelectorAll('.grp-damage-popup')).toHaveLength(0);

    act(() => { vi.advanceTimersByTime(120); }); // 누적 1020ms(tick 8 통과) — tickCount(8)가 invulnerableUntilTick(8) 이상이 되어
    // 계속 접촉 중인 함정이 다시 피해를 입혀 새 표시가 뜬다.
    expect(container.querySelectorAll('.grp-damage-popup')).toHaveLength(1);
  });
});
