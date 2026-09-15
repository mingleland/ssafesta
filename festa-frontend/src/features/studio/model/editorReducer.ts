// Booth Studio 편집기의 단일 상태 기계 — 오브젝트 배치·선택·저장 상태를 이산 액션으로만 바꾼다
// 액션을 이산화해 둔 덕에 undo/redo 를 스냅샷 스택으로 붙일 수 있었다 (S15P21A604-605)
// 출처: specs/005-booth-studio-layout/FE/data-model.md EditorState, research.md R-07

import type { LayoutObject, ObjectType } from '../../../entities/layout/types';
import { normalizeRotation } from '../lib/coords';

export type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';

export interface EditorState {
  boothId: number;
  template: string;
  objects: LayoutObject[];
  selectedObjectId: string | null;
  dirty: boolean;
  saveStatus: SaveStatus;
  // saveStatus와 분리 — 편집 액션이 dirty로 되돌려도 배너·재로드 버튼이 사라지면 안 된다(T026 결함4).
  // LOAD_DRAFT(재로드)만 해소하고, 편집 자체는 계속 허용하되 저장은 막는다(낡은 baseRevision 재실패 루프 방지).
  conflict: boolean;
  baseRevision: number; // 마지막으로 읽은 revision — PUT 시 expectedRevision으로 echo (R-06)
  publishedVersion: number | null;
  /**
   * 되돌리기 스택 (S15P21A604-605). 배치(objects)만 담는다 — 저장 상태·충돌·revision 은
   * 서버와의 관계라 되돌릴 대상이 아니다.
   */
  past: LayoutObject[][];
  future: LayoutObject[][];
  /**
   * 직전 편집이 무엇이었는지 — 연속 드래그를 한 덩어리로 묶는 데만 쓴다.
   *
   * ponytail: 같은 오브젝트의 이동·회전이 이어지면 스냅샷을 합친다. 드래그 종료 신호가
   * 없어서(두 렌더러 계약에 onMoveEnd 가 없다) 이 방식으로 근사했다 — 이동 후 곧바로 같은
   * 오브젝트를 또 옮기면 한 번에 되돌아간다. 정확히 가르려면 렌더러가 드래그 끝을 알려야 한다.
   */
  lastEdit: { kind: 'move' | 'rotate'; objectId: string } | null;
}

/** 히스토리 상한 — 스냅샷이 배치 배열이라 가볍지만 무한히 쌓을 이유는 없다 */
const HISTORY_LIMIT = 50;

export type EditorAction =
  | { type: 'LOAD_DRAFT'; boothId: number; template: string; objects: LayoutObject[]; revision: number; publishedVersion: number | null }
  | { type: 'ADD_OBJECT'; objectType: ObjectType; x: number; z: number; assetCode?: string }
  | { type: 'MOVE_OBJECT'; objectId: string; x: number; z: number }
  | { type: 'ROTATE_OBJECT'; objectId: string; rotationY: number }
  | { type: 'REMOVE_OBJECT'; objectId: string }
  // 배치 전체 교체 — 템플릿 적용과 전체 초기화가 함께 쓴다. ADD_OBJECT 를 반복하면 기존 배치
  // 위에 쌓이므로(감사 항목 4) 템플릿은 "덮어쓰기"라는 뜻을 액션 하나로 표현한다.
  | { type: 'REPLACE_OBJECTS'; objects: LayoutObject[] }
  | { type: 'SELECT_OBJECT'; objectId: string | null }
  | { type: 'LINK_CONTENT'; objectId: string; configId: number | undefined }
  | { type: 'SET_ASSET_CODE'; objectId: string; assetCode: string | undefined }
  | { type: 'SAVE_START' }
  | { type: 'SAVE_SUCCESS'; revision: number }
  | { type: 'SAVE_ERROR' }
  | { type: 'SAVE_CONFLICT' }
  | { type: 'PUBLISH_SUCCESS'; publishedVersion: number }
  | { type: 'UNDO' }
  | { type: 'REDO' };

export function createInitialState(boothId: number): EditorState {
  return {
    boothId,
    template: 'PROJECT_EXHIBITION',
    objects: [],
    selectedObjectId: null,
    dirty: false,
    saveStatus: 'idle',
    conflict: false,
    baseRevision: 0,
    publishedVersion: null,
    past: [],
    future: [],
    lastEdit: null,
  };
}

/**
 * 편집 결과에 히스토리를 얹는다 — 편집 액션은 전부 이 함수를 통과한다.
 *
 * `coalesceWith` 가 직전 편집과 같으면 스냅샷을 새로 쌓지 않는다(연속 드래그 1회 = 1스냅샷).
 * redo 스택은 새 편집이 들어오는 순간 버린다 — 갈라진 미래를 남겨 두면 어느 쪽으로 가는지
 * 사용자가 알 수 없다.
 */
function edited(
  state: EditorState,
  next: Partial<EditorState> & { objects: LayoutObject[] },
  coalesceWith: EditorState['lastEdit'] = null,
): EditorState {
  const merge =
    coalesceWith !== null &&
    state.lastEdit !== null &&
    state.lastEdit.kind === coalesceWith.kind &&
    state.lastEdit.objectId === coalesceWith.objectId;
  return {
    ...state,
    ...next,
    dirty: true,
    saveStatus: 'dirty',
    past: merge ? state.past : [...state.past, state.objects].slice(-HISTORY_LIMIT),
    future: [],
    lastEdit: coalesceWith,
  };
}

export function editorReducer(state: EditorState, action: EditorAction): EditorState {
  switch (action.type) {
    case 'LOAD_DRAFT':
      return {
        ...state,
        boothId: action.boothId,
        template: action.template,
        objects: action.objects,
        baseRevision: action.revision,
        publishedVersion: action.publishedVersion,
        dirty: false,
        saveStatus: 'idle',
        conflict: false,
        // 저장 성공→refetch나 충돌 재로드로 새 objects가 들어와도 선택하던 오브젝트가
        // 여전히 있으면 선택을 유지한다 — 매번 무조건 풀면 저장할 때마다 PropertiesPanel이 닫힌다.
        selectedObjectId: action.objects.some((o) => o.objectId === state.selectedObjectId)
          ? state.selectedObjectId
          : null,
        // 서버본이 새 기준선이다 — 그 앞의 되돌리기는 의미가 없다
        past: [],
        future: [],
        lastEdit: null,
      };

    case 'ADD_OBJECT': {
      const newObject: LayoutObject = {
        objectId: crypto.randomUUID(), // 저장 전 편집기 내부 식별용 — R-02
        type: action.objectType,
        position: { x: action.x, y: 0, z: action.z },
        rotationY: 0,
        // 팔레트가 고른 장식 외형 코드 — 계약 필드 assetCode 그대로. 없으면 필드 자체를 생략한다(서버 NON_NULL)
        ...(action.assetCode !== undefined ? { assetCode: action.assetCode } : {}),
      };
      return edited(state, {
        objects: [...state.objects, newObject],
        selectedObjectId: newObject.objectId,
      });
    }

    case 'MOVE_OBJECT':
      return edited(
        state,
        {
          objects: state.objects.map((o) =>
            o.objectId === action.objectId ? { ...o, position: { ...o.position, x: action.x, z: action.z } } : o,
          ),
        },
        { kind: 'move', objectId: action.objectId },
      );

    case 'ROTATE_OBJECT':
      return edited(
        state,
        {
          objects: state.objects.map((o) =>
            o.objectId === action.objectId ? { ...o, rotationY: normalizeRotation(action.rotationY) } : o,
          ),
        },
        { kind: 'rotate', objectId: action.objectId },
      );

    case 'REMOVE_OBJECT':
      return edited(state, {
        objects: state.objects.filter((o) => o.objectId !== action.objectId),
        selectedObjectId: state.selectedObjectId === action.objectId ? null : state.selectedObjectId,
      });

    case 'REPLACE_OBJECTS':
      return edited(state, {
        objects: action.objects,
        // 교체하면 이전 선택은 더 이상 존재하지 않는다. 새 배치가 비어 있지 않으면 첫 오브젝트를
        // 잡아 Inspector 가 빈 채로 남지 않게 한다 — 초기화(빈 배열)면 선택도 없다.
        selectedObjectId: action.objects[0]?.objectId ?? null,
      });

    case 'SELECT_OBJECT':
      return { ...state, selectedObjectId: action.objectId };

    case 'LINK_CONTENT':
      return edited(state, {
        objects: state.objects.map((o) =>
          o.objectId === action.objectId ? { ...o, configId: action.configId } : o,
        ),
      });

    case 'SET_ASSET_CODE':
      return edited(state, {
        objects: state.objects.map((o) =>
          o.objectId === action.objectId ? { ...o, assetCode: action.assetCode } : o,
        ),
      });

    case 'SAVE_START':
      return { ...state, saveStatus: 'saving' };

    case 'SAVE_SUCCESS':
      return { ...state, saveStatus: 'saved', dirty: false, baseRevision: action.revision };

    case 'SAVE_ERROR':
      return { ...state, saveStatus: 'error' };

    case 'SAVE_CONFLICT':
      return { ...state, saveStatus: 'error', conflict: true };

    case 'UNDO': {
      const previous = state.past.at(-1);
      if (previous === undefined) return state;
      return {
        ...state,
        objects: previous,
        past: state.past.slice(0, -1),
        future: [state.objects, ...state.future].slice(0, HISTORY_LIMIT),
        // 되돌린 배치에 없는 선택은 푼다 — Inspector 가 사라진 오브젝트를 가리키면 안 된다
        selectedObjectId: previous.some((o) => o.objectId === state.selectedObjectId)
          ? state.selectedObjectId
          : null,
        // 되돌려도 서버본과는 여전히 다르다 — 저장 버튼이 살아 있어야 한다
        dirty: true,
        saveStatus: 'dirty',
        lastEdit: null,
      };
    }

    case 'REDO': {
      const next = state.future[0];
      if (next === undefined) return state;
      return {
        ...state,
        objects: next,
        past: [...state.past, state.objects].slice(-HISTORY_LIMIT),
        future: state.future.slice(1),
        selectedObjectId: next.some((o) => o.objectId === state.selectedObjectId)
          ? state.selectedObjectId
          : null,
        dirty: true,
        saveStatus: 'dirty',
        lastEdit: null,
      };
    }

    case 'PUBLISH_SUCCESS':
      return { ...state, publishedVersion: action.publishedVersion };

    default:
      return state;
  }
}
