// @vitest-environment jsdom
// S15P21A604-494 — DialogueEditor의 NODES rail에 씬 목록과 동일한 패턴(드래그 정렬 핸들·
// "시작으로 설정"·삭제)을 추가한 것을 실제 컴포넌트를 마운트해서 검증한다. 모델 레벨 함수
// (reorderDialogueNode/startNodeChangeReason·setStartNode/dialogueNodeRemovalReason·
// removeDialogueNode) 자체는 authoringCommands.test.ts가 이미 검증하므로, 여기서는 그
// 배선(드래그 이벤트 → 실제 호출, 버튼 disabled/title)만 확인한다.
//
// S15P21A604-513 — 이 기능이 살던 NODES rail 자체를 삭제하고 FLOW OVERVIEW로 옮겼다.
// 처음에는 rail의 행(.gss-node-row: ☰ 핸들 + 본문 버튼 + ★ + ×)을 그대로 포팅했지만,
// 실제로 화면에서 보니 좁은 rail 전용으로 만든 "가로 4요소 배치"가 FLOW OVERVIEW의 가로
// 스크롤 고정폭 카드 위에 얹히면서 어색했다(카드 밖에 툴바가 따로 뜨는 모양). 최종적으로
// 카드 하나(.gss-flow-node-card)가 곧 드래그 대상이자 선택 버튼이 되도록 합치고, ★/×는
// 카드 우측 상단에 겹쳐서(overlay) 배치했다. 카드 안에 진짜 <button>(★/×)을 넣어야 해서
// 카드 루트는 <button>이 아니라 role="button" + tabIndex의 <div>로 바꿨다 — <button> 안에
// <button>을 넣는 건 HTML 표준상 불가능(인터랙티브 콘텐츠 중첩 금지)해서 브라우저가
// 파싱 중 바깥 버튼을 미리 닫아버리기 때문.
import { useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DialogueEditor } from '../../studio/ui/DialogueEditor.tsx';
import { addDialogueNode, addDialogueScene } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { parseGameProject, type DialogueScene, type GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 494;

const buildProject = (): { project: GameProject; sceneId: string } => {
  let project = addDialogueScene(parseGameProject(createStarterProject(GAME_ID)), 'OVERLAY');
  const sceneId = project.scenes.at(-1)?.id;
  if (sceneId === undefined) throw new Error('expected a newly added DIALOGUE scene');
  const second = addDialogueNode(project, sceneId);
  project = second.project;
  const third = addDialogueNode(project, sceneId);
  project = third.project;
  return { project, sceneId };
};

// DialogueEditor는 onApply로만 상위에 변경을 알리는 controlled 컴포넌트라, 실제 반영된
// project를 테스트에서 확인할 수 있도록 최소한의 상태 하네스로 감싼다. 노드 순서/시작
// 노드는 DOM 텍스트만으로 구분하기 어려워(기본 노드들이 전부 같은 텍스트) 숨은 요약을
// data-testid로 노출한다.
const Harness = ({ initialProject, sceneId }: { initialProject: GameProject; sceneId: string }) => {
  const [project, setProject] = useState(initialProject);
  const scene = project.scenes.find((candidate) => candidate.id === sceneId) as DialogueScene;
  return (
    <>
      <div data-testid="node-order">{scene.nodes.map((node) => node.id).join(',')}</div>
      <div data-testid="start-node">{scene.startNodeId}</div>
      <DialogueEditor assetUrls={{}} onApply={setProject} project={project} scene={scene} />
    </>
  );
};

const setup = () => {
  const { project, sceneId } = buildProject();
  const { container } = render(<Harness initialProject={project} sceneId={sceneId} />);
  return { container };
};

describe('DialogueEditor — 노드 정렬/시작 설정/삭제(S15P21A604-494)', () => {
  it('카드를 드래그하면 노드 순서를 바꿀 수 있다', () => {
    const { container } = setup();
    const before = screen.getByTestId('node-order').textContent?.split(',') ?? [];
    expect(before).toHaveLength(3);

    const cards = container.querySelectorAll('.gss-flow-node-card');
    const firstCard = cards[0] as HTMLElement;
    const thirdCard = cards[2] as HTMLElement;

    fireEvent.dragStart(firstCard);
    fireEvent.dragOver(thirdCard);
    fireEvent.drop(thirdCard);

    const after = screen.getByTestId('node-order').textContent?.split(',') ?? [];
    expect(after).toEqual([before[1], before[2], before[0]]);
  });

  it('"시작으로 설정" 버튼을 누르면 그 노드가 새 START가 되고, 이미 START인 노드는 버튼이 비활성화된다', () => {
    const { container } = setup();
    const startBefore = screen.getByTestId('node-order').textContent?.split(',')[0];
    expect(screen.getByTestId('start-node').textContent).toBe(startBefore);

    const cards = container.querySelectorAll('.gss-flow-node-card');
    const firstStartButton = cards[0]?.querySelector('[aria-label="1번째 노드를 시작으로 설정"]') as HTMLButtonElement;
    expect(firstStartButton.disabled).toBe(true);

    const secondStartButton = cards[1]?.querySelector('[aria-label="2번째 노드를 시작으로 설정"]') as HTMLButtonElement;
    expect(secondStartButton.disabled).toBe(false);
    fireEvent.click(secondStartButton);

    const nodeOrder = screen.getByTestId('node-order').textContent?.split(',') ?? [];
    expect(screen.getByTestId('start-node').textContent).toBe(nodeOrder[1]);
  });

  it('일반 노드는 삭제되고, START 노드의 삭제 버튼은 비활성화된 채 이유가 붙는다', () => {
    const { container } = setup();
    const cards = container.querySelectorAll('.gss-flow-node-card');

    const firstRemoveButton = cards[0]?.querySelector('[aria-label="1번째 노드 삭제"]') as HTMLButtonElement;
    expect(firstRemoveButton.disabled).toBe(true);
    expect(firstRemoveButton.title).toBe('시작 노드는 삭제할 수 없습니다. 다른 노드를 시작으로 설정한 뒤 삭제하세요.');

    const thirdRemoveButton = cards[2]?.querySelector('[aria-label="3번째 노드 삭제"]') as HTMLButtonElement;
    expect(thirdRemoveButton.disabled).toBe(false);
    fireEvent.click(thirdRemoveButton);

    expect(screen.getByTestId('node-order').textContent?.split(',')).toHaveLength(2);
  });
});

describe('DialogueEditor — NODES rail을 FLOW OVERVIEW로 통합(S15P21A604-513)', () => {
  it('별도의 NODES rail은 더 이상 렌더링되지 않는다', () => {
    const { container } = setup();
    expect(container.querySelector('.gss-node-rail')).toBeNull();
  });

  it('FLOW OVERVIEW 각 카드는 고정 순번 배지 없이 노드 관리 버튼(★·×)만 보여준다', () => {
    const { container } = setup();
    const cards = container.querySelectorAll('.gss-dialogue-flow-list .gss-flow-node-card');
    expect(cards.length).toBe(3);
    for (const card of cards) {
      // 배열 인덱스 기반 번호는 드래그로 순서가 바뀌면 같이 바뀌어 "고정된 이름표"처럼
      // 오인하기 쉬웠다(NODES rail 때와 동일한 이유) — 아무 <span>도 번호 전용으로 쓰지 않는다.
      expect(card.querySelector('span')).toBeNull();
    }
  });

  it('"+ 노드 추가" 버튼이 FLOW OVERVIEW 헤더로 옮겨져 여전히 노드를 추가할 수 있다', () => {
    const { container } = setup();
    const addButton = container.querySelector('.gss-dialogue-flow header [aria-label="대화 노드 추가"]') as HTMLButtonElement;
    expect(addButton).not.toBeNull();
    fireEvent.click(addButton);
    expect(screen.getByTestId('node-order').textContent?.split(',')).toHaveLength(4);
  });

  // S15P21A604-513 (3차) — ☰ 드래그 전용 핸들을 없애고 카드 자체를 드래그 대상으로
  // 바꿨다. ★/×는 카드 "안"(우측 상단에 겹쳐서)으로 옮겨 카드 밖에 별도 툴바가 뜨는
  // 어색함을 없앴다.
  it('☰ 드래그 핸들은 더 이상 존재하지 않고, ★/×는 카드 안에 있다', () => {
    const { container } = setup();
    const cards = container.querySelectorAll('.gss-dialogue-flow-list .gss-flow-node-card');
    for (const card of cards) {
      expect(card.querySelector('.gss-drag-handle')).toBeNull();
      expect(card.querySelector('.gss-icon-button')).not.toBeNull();
    }
  });

  it('× 클릭이 다른 카드의 선택 상태를 건드리지 않는다(선택 클릭과 분리)', () => {
    // ★는 "시작으로 설정"이 선택도 함께 따라가게 하는 부수효과(useEffect)가 있어
    // 버블링 여부와 무관하게 결과가 같아져(-513 시도 중 실제로 겪은 오판) 이 회귀를
    // 검증하기에 적합하지 않다. × 삭제는 그런 부수효과가 없어서 버블링 여부가 결과에
    // 그대로 드러난다 — 만약 stopPropagation이 빠지면, 세 번째 카드의 × 클릭이 카드
    // 자체의 onClick(선택)도 같이 태워서 선택이 세 번째 노드(곧 삭제될 노드)로 바뀌고,
    // 그 노드가 사라진 뒤 selectedNode 폴백(scene.nodes[0])이 첫 번째 카드로 넘어가
    // 두 번째 카드에 있던 선택이 사라져 버린다.
    const { container } = setup();
    const cards = container.querySelectorAll('.gss-dialogue-flow-list .gss-flow-node-card');
    fireEvent.keyDown(cards[1] as HTMLElement, { key: 'Enter' });
    expect(cards[1]?.getAttribute('aria-current')).toBe('step');

    const thirdRemoveButton = cards[2]?.querySelector('[aria-label="3번째 노드 삭제"]') as HTMLButtonElement;
    fireEvent.click(thirdRemoveButton);

    const remainingCards = container.querySelectorAll('.gss-dialogue-flow-list .gss-flow-node-card');
    expect(remainingCards.length).toBe(2);
    expect(remainingCards[1]?.getAttribute('aria-current')).toBe('step');
  });

  it('카드에 포커스한 뒤 Enter를 누르면 그 노드가 선택된다(키보드 접근성)', () => {
    const { container } = setup();
    const cards = container.querySelectorAll('.gss-dialogue-flow-list .gss-flow-node-card');
    const thirdCard = cards[2] as HTMLElement;
    expect(thirdCard.getAttribute('role')).toBe('button');
    expect(thirdCard.getAttribute('tabindex')).toBe('0');
    fireEvent.keyDown(thirdCard, { key: 'Enter' });
    expect(thirdCard.getAttribute('aria-current')).toBe('step');
  });
});
