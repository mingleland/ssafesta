// @vitest-environment jsdom
// S15P21A604-360 — Event Action 순서 변경(햄버거 핸들 드래그)의 회귀 방어.
// 커맨드 레이어(authoringCommands.test.ts)는 reorderEventAction이 TERMINAL_ACTION_NOT_LAST를
// 올바르게 던지는 것까지는 검증하지만, 이 UI가 실제로 지켜야 하는 것은 "애초에 그 예외가 날
// 드롭을 만들지 않는 것"이다 — 드롭 가능 최대 인덱스(maxDropIndex)를 terminal Action 앞쪽으로
// clamp해서, 규칙을 어기는 드롭 자체가 발생하지 않게 한다. 이 테스트는 그 clamp와, terminal
// Action의 핸들 자체가 애초에 draggable하지 않은 것을 함께 고정한다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { EventEditor } from '../../studio/ui/EventEditor.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

afterEach(() => {
  cleanup();
});

// jsdom의 DragEvent는 dataTransfer를 기본 구현하지 않는다 — 실제 값을 옮기지는 않지만
// (재정렬 대상은 React 컴포넌트 상태로 추적한다) 핸들러가 호출하는 setData가 죽지 않도록 채운다.
const dataTransferStub = { setData: () => undefined };

const setup = (objectId: string) => {
  const project = createStarterProject(360);
  const scene = project.scenes.find((candidate) => candidate.id === 'library');
  if (scene?.type !== 'TOP_DOWN') throw new Error('expected library scene');
  const selectedObject = scene.objects.find((object) => object.id === objectId);
  if (selectedObject === undefined) throw new Error(`expected ${objectId} object`);
  const onApply = vi.fn();
  const { container } = render(
    <EventEditor onApply={onApply} project={project} scene={scene} selectedObject={selectedObject} />,
  );
  const rows = [...container.querySelectorAll('.gss-rule-row--action')] as HTMLElement[];
  const handles = [...container.querySelectorAll('.gss-drag-handle')] as HTMLButtonElement[];
  return { onApply, rows, handles };
};

describe('EventEditor — Action 드래그 순서 변경', () => {
  it('openLockedDoor([SET_VARIABLE, GO_TO_SCENE])에서 terminal Action의 핸들은 애초에 드래그할 수 없다', () => {
    const { handles } = setup('lockedDoor');
    expect(handles).toHaveLength(2);
    expect(handles[0].disabled).toBe(false);
    expect(handles[0].draggable).toBe(true);
    expect(handles[1].disabled).toBe(true); // GO_TO_SCENE — 마지막 자리에 고정된다.
    expect(handles[1].draggable).toBe(false);
  });

  it('non-terminal Action을 terminal Action 자리로 드롭해도 마지막 앞자리로 clamp되어 순서가 그대로면 onApply를 호출하지 않는다', () => {
    // openLockedDoor: [SET_VARIABLE(0), GO_TO_SCENE(1)] — maxDropIndex는 0(=GO_TO_SCENE 바로 앞)이라,
    // SET_VARIABLE(0)을 GO_TO_SCENE(1) 위에 놓아도 clamp된 목적지(0)가 원래 자리와 같아 아무 일도
    // 일어나지 않아야 한다 — 이게 "terminal Action 뒤로는 못 간다"는 규칙을 UI가 지키는 방식이다.
    const { onApply, rows, handles } = setup('lockedDoor');

    fireEvent.dragStart(handles[0], { dataTransfer: dataTransferStub });
    fireEvent.dragOver(rows[1], { dataTransfer: dataTransferStub });
    fireEvent.drop(rows[1], { dataTransfer: dataTransferStub });

    expect(onApply).not.toHaveBeenCalled();
  });

  it('non-terminal Action끼리는 드래그로 자유롭게 순서를 바꿀 수 있고, onApply가 재정렬된 project로 한 번 호출된다', () => {
    // takeLibraryKey: [GIVE_ITEM(0), HIDE_OBJECT(1)] — 둘 다 non-terminal이라 자유롭게 이동 가능.
    const { onApply, rows, handles } = setup('libraryKeyObject');

    fireEvent.dragStart(handles[0], { dataTransfer: dataTransferStub });
    fireEvent.dragOver(rows[1], { dataTransfer: dataTransferStub });
    fireEvent.drop(rows[1], { dataTransfer: dataTransferStub });

    expect(onApply).toHaveBeenCalledTimes(1);
    const nextProject = onApply.mock.calls[0][0];
    const nextScene = nextProject.scenes.find((candidate: { id: string }) => candidate.id === 'library');
    const nextEvent = nextScene.events.find((candidate: { id: string }) => candidate.id === 'takeLibraryKey');
    expect(nextEvent.actions.map((action: { type: string }) => action.type)).toEqual(['HIDE_OBJECT', 'GIVE_ITEM']);
  });

  it('drop 없이 dragEnd만 발생해도(취소된 드래그) 드래그 상태가 정리되고 onApply는 호출되지 않는다', () => {
    const { onApply, handles } = setup('libraryKeyObject');

    fireEvent.dragStart(handles[0], { dataTransfer: dataTransferStub });
    fireEvent.dragEnd(handles[0]);

    expect(onApply).not.toHaveBeenCalled();
  });
});
