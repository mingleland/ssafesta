// @vitest-environment jsdom
// S15P21A604-532 — 상호작용 범위 안에 들어온 오브젝트 위에 안내 문구(prompt)가 뜨는지
// 컴포넌트 레벨에서 확인한다. currentInteractionTarget 자체(정면 판정, 범위 밖이면 null
// 등)는 referenceRuntime.test.ts가 이미 순수 함수 레벨에서 검증했으므로, 여기서는 그 값을
// 컴포넌트가 실제로 화면에 반영하는가(조건 분기, 이름표와 안 겹치는 위치)만 본다.
import { act } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { parseGameProject } from '../../contracts/gameProject.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

// S15P21A604-363의 tick 이펙트는 실제 setInterval(120ms)을 쓴다. 가짜 타이머 없이 여러
// 테스트를 한 파일에서 돌리면, 테스트 사이의 비동기 yield 지점에서 그 real interval이
// 예상치 못하게 한 번 더 발화해 캐릭터가 탭 횟수보다 더 움직이는 산발적 실패가 났다 —
// 기존 referenceGamePlayerHeldKeyMovement.test.tsx/referenceGamePlayerDamagePopup.test.tsx와
// 동일하게 가짜 타이머로 고정해 실제 시간 흐름을 완전히 통제한다.
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

// S15P21A604-363 held-key 로직은 keydown에서 즉시 setRuntime을 예약하고, keyup은 ref에서
// 방향을 바로 지운다. keydown+keyup을 같은 act() 안에서 한꺼번에 쏘면, React가 그 setRuntime
// 업데이트를 아직 flush하기 전에 keyup이 먼저 ref를 비워버려서(둘 다 동기 DOM 리스너라
// React 배치와 무관하게 순서대로 실행됨) movePlayerFromHeldKeys가 빈 집합을 보고 아무것도
// 안 움직이는 채로 렌더된다 — 실제 사용자 입력은 keydown과 keyup 사이에 실제 시간차가
// 있어 이 경쟁이 일어나지 않는다. 그래서 keydown과 keyup을 반드시 별도의 act()로 나눠
// React가 keydown의 상태 갱신을 먼저 반영하게 한다.
const tap = (key: string) => {
  act(() => { fireEvent.keyDown(window, { key }); });
  act(() => { fireEvent.keyUp(window, { key }); });
};

// starter project(createStarterProject.ts) — 스폰 (8,8), librarian(INTERACTABLE,
// prompt: '대화하기')은 (7,4). LEFT 1회 + UP 3회(전부 탭, 즉시 한 칸씩)로 (7,5)에서
// 위를 보게 되어 librarian과 정면으로 마주본다(referenceRuntime.test.ts와 동일 경로).
const walkNextToLibrarian = () => {
  tap('ArrowLeft');
  tap('ArrowUp');
  tap('ArrowUp');
  tap('ArrowUp');
};

describe('ReferenceGamePlayer — 오브젝트 위 상호작용 안내 문구(S15P21A604-532)', () => {
  it('상호작용 범위에 들어가면 오브젝트 위에 안내 문구가 뜨고, 벗어나면 사라진다', () => {
    const { container } = setup(createStarterProject(532));
    expect(container.querySelector('.grp-interaction-hint')).toBeNull();

    walkNextToLibrarian();
    expect(container.querySelector('.grp-interaction-hint')?.textContent).toBe('대화하기');

    // 다시 아래로 한 칸 물러나면 정면이 더 이상 librarian이 아니게 되어 힌트가 사라져야 한다.
    tap('ArrowDown');
    expect(container.querySelector('.grp-interaction-hint')).toBeNull();
  });

  it('INTERACTABLE 컴포넌트 없이 이벤트로만 상호작용 가능한 오브젝트는 힌트가 뜨지 않는다', () => {
    const original = createStarterProject(532);
    // 레버는 스폰(8,8) 바로 옆(9,8)에 둔다 — librarian(7,4) 쪽 경로와 겹치는 자리에 두면
    // "레버 위에 서 있음(onCurrent)"과 "librarian이 정면(inFront)"이 동시에 성립해 버려서
    // scene.objects 배열상 먼저 나오는 librarian이 대상으로 잡히는 우연이 생겼었다(디버깅
    // 중 실제로 겪음) — 완전히 무관한 자리에 둬서 레버만 단독으로 후보가 되게 한다.
    const project = parseGameProject({
      ...original,
      scenes: original.scenes.map((scene) => scene.id !== 'library' || scene.type !== 'TOP_DOWN' ? scene : {
        ...scene,
        objects: [...scene.objects, {
          id: 'leverNoPrompt',
          preset: 'INTERACTABLE' as const,
          position: { x: 9, y: 8 },
          visible: true,
          components: [],
        }],
        events: [...scene.events, {
          id: 'pullLever',
          trigger: { type: 'ON_INTERACT' as const, targetId: 'leverNoPrompt' },
          conditions: [],
          actions: [{ type: 'SET_VARIABLE' as const, variableId: 'doorOpened', value: true }],
        }],
      }),
    });
    const { container } = setup(project);

    // 스폰(8,8) → RIGHT 1회로 레버(9,8) 칸에 바로 올라선다(onCurrent).
    tap('ArrowRight');

    expect(container.querySelector('.grp-interaction-hint')).toBeNull();
    // 대상 자체는 여전히 잡혀야 하므로(E를 누르면 동작해야 함), 좌측 하단 안내 라벨은
    // 그대로 남아 있다(S15P21A604-540 — 클릭 가능한 버튼이 아니라 순수 안내 텍스트다).
    expect(screen.getByText('E 상호작용')).not.toBeNull();
  });

  it('좌측 하단 상호작용 안내는 그대로 "E 상호작용" 텍스트이고, 버튼이 아니다(S15P21A604-540 회귀 없음)', () => {
    setup(createStarterProject(532));
    walkNextToLibrarian();
    expect(screen.getByText('E 상호작용')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'E 상호작용' })).toBeNull();
  });
});
