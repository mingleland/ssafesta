// Booth Studio 되돌리기 (S15P21A604-605).
//
// 가장 중요한 것은 **연속 드래그가 한 덩어리로 묶이는가** 다. `MOVE_OBJECT` 는 드래그 중 매
// 프레임 dispatch 되므로 그대로 쌓으면 Undo 한 번이 1px 씩만 되돌린다 — 되돌리기가 있으나 마나
// 한 상태가 된다. 그래서 스냅샷 병합을 먼저 고정한다.
import { describe, expect, it } from 'vitest';
import { createInitialState, editorReducer } from '../../model/editorReducer';
import type { EditorAction, EditorState } from '../../model/editorReducer';
import type { LayoutObject } from '../../../../entities/layout/types';

const BOOTH_ID = 7;

function object(objectId: string, x: number): LayoutObject {
  return { objectId, type: 'DECORATION', position: { x, y: 0, z: 0 }, rotationY: 0 };
}

function loaded(objects: LayoutObject[]): EditorState {
  return editorReducer(createInitialState(BOOTH_ID), {
    type: 'LOAD_DRAFT',
    boothId: BOOTH_ID,
    template: 'PROJECT_EXHIBITION',
    objects,
    revision: 3,
    publishedVersion: null,
  });
}

function run(state: EditorState, ...actions: EditorAction[]): EditorState {
  return actions.reduce(editorReducer, state);
}

const xs = (state: EditorState) => state.objects.map((o) => o.position.x);

describe('되돌리기 — 연속 드래그는 한 덩어리다', () => {
  it('한 오브젝트를 여러 번 옮겨도 Undo 한 번에 출발점으로 돌아온다', () => {
    const start = loaded([object('a', 0)]);
    const dragged = run(
      start,
      { type: 'MOVE_OBJECT', objectId: 'a', x: 1, z: 0 },
      { type: 'MOVE_OBJECT', objectId: 'a', x: 2, z: 0 },
      { type: 'MOVE_OBJECT', objectId: 'a', x: 3, z: 0 },
    );
    expect(xs(dragged)).toEqual([3]);
    expect(dragged.past.length).toBe(1);

    expect(xs(editorReducer(dragged, { type: 'UNDO' }))).toEqual([0]);
  });

  it('다른 오브젝트를 옮기면 새 덩어리가 된다', () => {
    const start = loaded([object('a', 0), object('b', 5)]);
    const moved = run(
      start,
      { type: 'MOVE_OBJECT', objectId: 'a', x: 1, z: 0 },
      { type: 'MOVE_OBJECT', objectId: 'b', x: 6, z: 0 },
    );
    expect(moved.past.length).toBe(2);

    const once = editorReducer(moved, { type: 'UNDO' });
    expect(xs(once)).toEqual([1, 5]); // b 만 돌아온다
  });

  it('이동과 회전은 서로 다른 덩어리다', () => {
    const start = loaded([object('a', 0)]);
    const both = run(
      start,
      { type: 'MOVE_OBJECT', objectId: 'a', x: 2, z: 0 },
      { type: 'ROTATE_OBJECT', objectId: 'a', rotationY: 90 },
    );
    expect(both.past.length).toBe(2);
    expect(editorReducer(both, { type: 'UNDO' }).objects[0].rotationY).toBe(0);
  });
});

describe('되돌리기 — 기본 동작', () => {
  it('추가·삭제를 되돌리고 다시 실행한다', () => {
    const start = loaded([object('a', 0)]);
    const added = editorReducer(start, { type: 'ADD_OBJECT', objectType: 'DECORATION', x: 1, z: 1 });
    expect(added.objects.length).toBe(2);

    const undone = editorReducer(added, { type: 'UNDO' });
    expect(undone.objects.length).toBe(1);
    expect(undone.future.length).toBe(1);

    const redone = editorReducer(undone, { type: 'REDO' });
    expect(redone.objects.length).toBe(2);
  });

  it('되돌릴 것이 없으면 상태가 그대로다', () => {
    const start = loaded([object('a', 0)]);
    expect(editorReducer(start, { type: 'UNDO' })).toBe(start);
    expect(editorReducer(start, { type: 'REDO' })).toBe(start);
  });

  it('되돌린 뒤 새로 편집하면 redo 스택을 버린다', () => {
    const start = loaded([object('a', 0)]);
    const undone = run(
      start,
      { type: 'MOVE_OBJECT', objectId: 'a', x: 2, z: 0 },
      { type: 'UNDO' },
    );
    expect(undone.future.length).toBe(1);

    const branched = editorReducer(undone, { type: 'MOVE_OBJECT', objectId: 'a', x: 9, z: 0 });
    // 갈라진 미래를 남겨 두면 사용자가 어느 쪽으로 가는지 알 수 없다
    expect(branched.future).toEqual([]);
  });

  it('되돌려도 dirty 는 유지된다 — 서버본과는 여전히 다르다', () => {
    const start = loaded([object('a', 0)]);
    const undone = run(start, { type: 'MOVE_OBJECT', objectId: 'a', x: 2, z: 0 }, { type: 'UNDO' });
    expect(undone.dirty).toBe(true);
    expect(undone.saveStatus).toBe('dirty');
  });

  it('되돌린 배치에 없는 선택은 푼다', () => {
    const start = loaded([object('a', 0)]);
    const added = editorReducer(start, { type: 'ADD_OBJECT', objectType: 'DECORATION', x: 1, z: 1 });
    expect(added.selectedObjectId).not.toBeNull();

    const undone = editorReducer(added, { type: 'UNDO' });
    expect(undone.selectedObjectId).toBeNull();
  });

  it('서버본을 다시 읽으면 히스토리가 비워진다 — 그 앞은 되돌릴 의미가 없다', () => {
    const start = loaded([object('a', 0)]);
    const edited = editorReducer(start, { type: 'MOVE_OBJECT', objectId: 'a', x: 2, z: 0 });
    expect(edited.past.length).toBe(1);

    const reloaded = editorReducer(edited, {
      type: 'LOAD_DRAFT',
      boothId: BOOTH_ID,
      template: 'PROJECT_EXHIBITION',
      objects: [object('a', 0)],
      revision: 4,
      publishedVersion: null,
    });
    expect(reloaded.past).toEqual([]);
    expect(reloaded.future).toEqual([]);
  });

  it('템플릿 교체도 되돌릴 수 있다', () => {
    const start = loaded([object('a', 0), object('b', 1)]);
    const replaced = editorReducer(start, { type: 'REPLACE_OBJECTS', objects: [] });
    expect(replaced.objects).toEqual([]);

    expect(editorReducer(replaced, { type: 'UNDO' }).objects.length).toBe(2);
  });
});
