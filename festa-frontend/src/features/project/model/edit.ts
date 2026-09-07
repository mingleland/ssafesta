// 소유자 편집 상태 기계 — draft + dirty 키 추적, 저장은 dirty 키만 PATCH 직렬화 (S15P21A604-134).
// BE PresenceField: 키 생략 = 유지, 명시적 null = 비우기 — 그래서 전체 객체 전송이 금지다.
import { useSyncExternalStore } from 'react';
import { projectApi } from '../../../entities/project/api.select';
import type { ProjectPatch, ProjectView } from '../../../entities/project/types';
import type { ProjectFieldKey } from '../../../shared/contracts/project';

export interface ProjectEditState {
  status: 'idle' | 'loading' | 'ready' | 'error';
  boothId: number | null;
  /** null = 아직 만든 프로젝트가 없음(0개 배열) — save 는 create 로 간다 */
  projectId: number | null;
  draft: Record<ProjectFieldKey, string | null>;
  dirty: ReadonlySet<ProjectFieldKey>;
  save: { phase: 'idle' | 'submitting' | 'success' | 'error' };
}

const EMPTY_DRAFT: Record<ProjectFieldKey, string | null> = {
  name: null,
  description: null,
  thumbnailUrl: null,
  videoUrl: null,
  deployUrl: null,
  gitUrl: null,
  portfolioUrl: null,
};

const initialState: ProjectEditState = {
  status: 'idle',
  boothId: null,
  projectId: null,
  draft: EMPTY_DRAFT,
  dirty: new Set(),
  save: { phase: 'idle' },
};

let state: ProjectEditState = initialState;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<ProjectEditState>): void {
  state = { ...state, ...patch };
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getProjectEditSnapshot(): ProjectEditState {
  return state;
}

export function useProjectEdit(): ProjectEditState {
  return useSyncExternalStore(subscribe, getProjectEditSnapshot);
}

function draftOf(view: ProjectView): Record<ProjectFieldKey, string | null> {
  return {
    name: view.name,
    description: view.description,
    thumbnailUrl: view.thumbnailUrl,
    videoUrl: view.videoUrl,
    deployUrl: view.deployUrl,
    gitUrl: view.gitUrl,
    portfolioUrl: view.portfolioUrl,
  };
}

export async function loadProjectEdit(boothId: number): Promise<void> {
  setState({ ...initialState, status: 'loading', boothId });
  try {
    const view = await projectApi.getMyProjects(boothId);
    if (state.boothId !== boothId) return; // 늦은 응답이 다른 부스 편집 상태를 덮지 않는다 (-377)
    const project = view.projects[0] ?? null;
    setState({
      status: 'ready',
      projectId: project?.projectId ?? null,
      draft: project ? draftOf(project) : EMPTY_DRAFT,
      dirty: new Set(),
    });
  } catch {
    if (state.boothId !== boothId) return;
    setState({ status: 'error' });
  }
}

export function updateField(key: ProjectFieldKey, value: string | null): void {
  // 저장 중 편집은 ① save.phase 리셋으로 이중 제출 가드를 해제하고(projectId null 이면 create 2회)
  // ② 저장 성공 응답이 그 편집을 조용히 덮어 유실시킨다 (-377) — 저장 완료까지 입력을 막는다
  if (state.save.phase === 'submitting') return;
  setState({
    draft: { ...state.draft, [key]: value },
    dirty: new Set(state.dirty).add(key),
    save: { phase: 'idle' },
  });
}

export async function saveProject(): Promise<void> {
  if (state.save.phase === 'submitting' || state.boothId === null) return;
  const patch: ProjectPatch = {};
  for (const key of state.dirty) patch[key] = state.draft[key];
  setState({ save: { phase: 'submitting' } });
  try {
    const saved = state.projectId === null
      ? await projectApi.createProject(state.boothId, patch)
      : await projectApi.updateProject(state.projectId, patch);
    setState({
      projectId: saved.projectId,
      draft: draftOf(saved),
      dirty: new Set(),
      save: { phase: 'success' },
    });
  } catch {
    setState({ save: { phase: 'error' } });
  }
}

export function __resetProjectEditForTests(): void {
  state = initialState;
  listeners.clear();
}
