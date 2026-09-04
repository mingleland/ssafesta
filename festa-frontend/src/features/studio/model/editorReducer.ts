// Booth Studio 편집기의 단일 상태 기계 — 오브젝트 배치·선택·저장 상태를 이산 액션으로만 바꾼다
// 액션을 이산화해 두면 P1의 undo/history가 액션 로그 재생으로 자연스럽게 확장된다 (history는 지금 구현하지 않음)
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
}

export type EditorAction =
  | { type: 'LOAD_DRAFT'; boothId: number; template: string; objects: LayoutObject[]; revision: number; publishedVersion: number | null }
  | { type: 'ADD_OBJECT'; objectType: ObjectType; x: number; z: number; assetCode?: string }
  | { type: 'MOVE_OBJECT'; objectId: string; x: number; z: number }
  | { type: 'ROTATE_OBJECT'; objectId: string; rotationY: number }
  | { type: 'REMOVE_OBJECT'; objectId: string }
  | { type: 'SELECT_OBJECT'; objectId: string | null }
  | { type: 'LINK_CONTENT'; objectId: string; configId: number | undefined }
  | { type: 'SET_ASSET_CODE'; objectId: string; assetCode: string | undefined }
  | { type: 'SAVE_START' }
  | { type: 'SAVE_SUCCESS'; revision: number }
  | { type: 'SAVE_ERROR' }
  | { type: 'SAVE_CONFLICT' }
  | { type: 'PUBLISH_SUCCESS'; publishedVersion: number };

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
      return {
        ...state,
        objects: [...state.objects, newObject],
        selectedObjectId: newObject.objectId,
        dirty: true,
        saveStatus: 'dirty',
      };
    }

    case 'MOVE_OBJECT':
      return {
        ...state,
        objects: state.objects.map((o) =>
          o.objectId === action.objectId ? { ...o, position: { ...o.position, x: action.x, z: action.z } } : o,
        ),
        dirty: true,
        saveStatus: 'dirty',
      };

    case 'ROTATE_OBJECT':
      return {
        ...state,
        objects: state.objects.map((o) =>
          o.objectId === action.objectId ? { ...o, rotationY: normalizeRotation(action.rotationY) } : o,
        ),
        dirty: true,
        saveStatus: 'dirty',
      };

    case 'REMOVE_OBJECT':
      return {
        ...state,
        objects: state.objects.filter((o) => o.objectId !== action.objectId),
        selectedObjectId: state.selectedObjectId === action.objectId ? null : state.selectedObjectId,
        dirty: true,
        saveStatus: 'dirty',
      };

    case 'SELECT_OBJECT':
      return { ...state, selectedObjectId: action.objectId };

    case 'LINK_CONTENT':
      return {
        ...state,
        objects: state.objects.map((o) =>
          o.objectId === action.objectId ? { ...o, configId: action.configId } : o,
        ),
        dirty: true,
        saveStatus: 'dirty',
      };

    case 'SET_ASSET_CODE':
      return {
        ...state,
        objects: state.objects.map((o) =>
          o.objectId === action.objectId ? { ...o, assetCode: action.assetCode } : o,
        ),
        dirty: true,
        saveStatus: 'dirty',
      };

    case 'SAVE_START':
      return { ...state, saveStatus: 'saving' };

    case 'SAVE_SUCCESS':
      return { ...state, saveStatus: 'saved', dirty: false, baseRevision: action.revision };

    case 'SAVE_ERROR':
      return { ...state, saveStatus: 'error' };

    case 'SAVE_CONFLICT':
      return { ...state, saveStatus: 'error', conflict: true };

    case 'PUBLISH_SUCCESS':
      return { ...state, publishedVersion: action.publishedVersion };

    default:
      return state;
  }
}
