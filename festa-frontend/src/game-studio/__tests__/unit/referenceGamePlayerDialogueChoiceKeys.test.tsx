// @vitest-environment jsdom
// S15P21A604-490 — 대화창(Overlay/Fullscreen 공용 렌더 경로) 선택지를 마우스 클릭 외에
// 숫자키로도 고를 수 있게 한다. 선택지 버튼에는 이미 번호 힌트(<span>{index+1}</span>)가
// 표시되고 있었지만 실제 숫자키 입력과 연결돼 있지 않았다(Notion QA id=27). 컴포넌트
// 레벨에서 keydown이 실제로 chooseReferenceDialogue를 올바른 조건에서만 호출하는지 검증한다
// — 대화 자체의 전이 로직(GO_TO_SCENE 등)은 referenceRuntime.test.ts 쪽 관심사라 여기서는
// 다루지 않는다.
import { fireEvent, render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { createBlankProject } from '../../studio/model/createBlankProject.ts';
import { parseGameProject, type GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 490;

// FULL_SCREEN 대화 씬을 시작 씬으로 두고(OVERLAY는 시작 씬으로 못 씀), 선택지 3개가 각각
// 서로 다른 TOP_DOWN 씬으로 GO_TO_SCENE 하도록 구성한다 — 어떤 선택지가 실제로 골라졌는지는
// 헤더에 표시되는 Scene 이름으로 구분한다.
const resultScene = (id: string, name: string): GameProject['scenes'][number] => ({
  id,
  type: 'TOP_DOWN',
  name,
  width: 8,
  height: 8,
  tileLayers: [],
  objects: [{ id: `${id}Spawn`, preset: 'PLAYER_SPAWN', position: { x: 4, y: 4 }, visible: true, components: [] }],
  events: [],
});

const buildProject = (): GameProject => {
  const base = createBlankProject(GAME_ID);
  return parseGameProject({
    ...base,
    startSceneId: 'choiceScene',
    scenes: [
      {
        id: 'choiceScene',
        type: 'DIALOGUE',
        name: '질문',
        presentation: 'FULL_SCREEN',
        startNodeId: 'ask',
        nodes: [{
          id: 'ask',
          speaker: '내레이션',
          text: '무엇을 고르시겠습니까?',
          choices: [
            { id: 'c1', text: '첫 번째', actions: [{ type: 'GO_TO_SCENE', sceneId: 'result1' }] },
            { id: 'c2', text: '두 번째', actions: [{ type: 'GO_TO_SCENE', sceneId: 'result2' }] },
            { id: 'c3', text: '세 번째', actions: [{ type: 'GO_TO_SCENE', sceneId: 'result3' }] },
          ],
        }],
      },
      resultScene('result1', '결과1'),
      resultScene('result2', '결과2'),
      resultScene('result3', '결과3'),
    ],
  });
};

const setup = () => render(
  <ReferenceGamePlayer
    mode="PREVIEW"
    onExit={() => undefined}
    project={buildProject()}
    sessionPort={createPreviewGameSessionPort()}
  />,
);

const currentSceneName = () => screen.getByText('Scene').nextElementSibling?.textContent ?? null;

describe('ReferenceGamePlayer — 대화 선택지 숫자키(S15P21A604-490)', () => {
  it('숫자 "2"를 누르면 두 번째 선택지가 골라진다', () => {
    setup();
    expect(currentSceneName()).toBe('질문');

    fireEvent.keyDown(window, { key: '2' });

    expect(currentSceneName()).toBe('결과2');
  });

  it('선택지 개수를 넘는 숫자를 눌러도 아무 변화가 없다', () => {
    setup();

    fireEvent.keyDown(window, { key: '9' });

    expect(currentSceneName()).toBe('질문');
  });

  it('OS auto-repeat(반복 keydown)은 무시하고, 최초 입력에만 반응한다', () => {
    setup();

    // 실제 키보드에서 계속 누르고 있으면 두 번째 이벤트부터 repeat: true로 온다 — 그 이벤트
    // 하나만 먼저 보내면 아직 아무 것도 선택되지 않아야 한다.
    fireEvent.keyDown(window, { key: '1', repeat: true });
    expect(currentSceneName()).toBe('질문');

    // repeat이 아닌 최초 입력은 정상적으로 반응해야 한다.
    fireEvent.keyDown(window, { key: '1', repeat: false });
    expect(currentSceneName()).toBe('결과1');
  });

  it('대화창이 열려 있지 않으면 숫자키가 아무 영향을 주지 않는다', () => {
    setup();
    // "1"을 눌러 대화를 벗어난 뒤(결과1 씬, 대화 없음)에는 숫자키가 더 이상 아무 것도
    // 하지 않아야 한다 — 예를 들어 이후 "2"를 눌러도 다른 씬으로 튀지 않는다.
    fireEvent.keyDown(window, { key: '1' });
    expect(currentSceneName()).toBe('결과1');

    fireEvent.keyDown(window, { key: '2' });
    expect(currentSceneName()).toBe('결과1');
  });
});
