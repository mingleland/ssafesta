// 방문자 전시 상태 기계 — openOverlay('PROJECT', { boothId }) intent 이후의 데이터 흐름 (S15P21A604-134).
// Unity BOOTH_PROJECT_INTERACT(-343)는 미구현 — mock 경로는 openOverlay 직접 호출이 진입점이고,
// 계약 확정 시 dispatcher 에 case 하나가 추가될 뿐 이 모델은 불변이다.
import { useSyncExternalStore } from 'react';
import { projectApi } from '../../../entities/project/api.select';
import { parseVideoEmbed, type VideoEmbed } from '../../../entities/project/videoEmbed';
import { getSessionSnapshot } from '../../auth/model/session';
import type { ProjectLikeVM, ProjectLinksVM } from '../../../shared/contracts/project';

export interface ExhibitionProjectVM {
  projectId: number;
  name: string;
  description: string | null;
  thumbnailUrl: string | null;
  /** videoUrl 이 없으면 null — 있으면 판정 결과(EMBED/LINK_ONLY/INVALID)를 그대로 노출 */
  video: VideoEmbed | null;
  links: ProjectLinksVM;
  like: ProjectLikeVM;
}

export interface ExhibitionState {
  status: 'idle' | 'loading' | 'ready' | 'empty' | 'error';
  boothId: number | null;
  projects: ExhibitionProjectVM[];
}

let state: ExhibitionState = { status: 'idle', boothId: null, projects: [] };
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(next: ExhibitionState): void {
  state = next;
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getExhibitionSnapshot(): ExhibitionState {
  return state;
}

export function useExhibition(): ExhibitionState {
  return useSyncExternalStore(subscribe, getExhibitionSnapshot);
}

export async function loadExhibition(boothId: number): Promise<void> {
  setState({ status: 'loading', boothId, projects: [] });
  try {
    const view = await projectApi.getPublishedProjects(boothId);
    // 응답이 도착했을 때 다른 부스로 이미 넘어갔으면 버린다(늦은 응답이 최신 화면을 덮는 레이스)
    if (state.boothId !== boothId) return;
    const projects = view.projects.map((p) => ({
      projectId: p.projectId,
      name: p.name,
      description: p.description,
      thumbnailUrl: p.thumbnailUrl,
      video: p.videoUrl === null ? null : parseVideoEmbed(p.videoUrl),
      links: { deployUrl: p.deployUrl, gitUrl: p.gitUrl, portfolioUrl: p.portfolioUrl },
      // -135(#122) 회원 전용 — 게스트·비로그인은 서버가 403 MEMBER_ONLY 라 토글을 열지 않는다
      like: {
        count: p.likeCount,
        likedByMe: p.likedByMe,
        canToggle: getSessionSnapshot().kind === 'member',
        pending: false,
        error: false,
      },
    }));
    setState({ status: projects.length === 0 ? 'empty' : 'ready', boothId, projects });
  } catch {
    if (state.boothId !== boothId) return;
    setState({ status: 'error', boothId, projects: [] });
  }
}

function patchLike(projectId: number, patch: Partial<ProjectLikeVM>): void {
  setState({
    ...state,
    projects: state.projects.map((p) => (p.projectId === projectId ? { ...p, like: { ...p.like, ...patch } } : p)),
  });
}

// 토글 endpoint 가 아니라 멱등 PUT/DELETE — 현재 likedByMe 로 메서드를 고른다(#122).
// 낙관적으로 먼저 바꾸고 응답의 두 값으로 확정한다. 실패하면 되돌리고 error 를 켠다 —
// 멱등이라 재시도를 그냥 걸어도 안전하다. pending 동안 재클릭은 무시(이중 요청 방지).
export async function toggleLike(projectId: number): Promise<void> {
  const project = state.projects.find((p) => p.projectId === projectId);
  if (!project || !project.like.canToggle || project.like.pending) return;
  const before = project.like;
  const next = !before.likedByMe;
  patchLike(projectId, { likedByMe: next, count: before.count + (next ? 1 : -1), pending: true, error: false });
  try {
    const view = next ? await projectApi.likeProject(projectId) : await projectApi.unlikeProject(projectId);
    if (!state.projects.some((p) => p.projectId === projectId)) return; // 부스 전환 후 도착
    patchLike(projectId, { likedByMe: view.likedByMe, count: view.likeCount, pending: false });
  } catch {
    if (!state.projects.some((p) => p.projectId === projectId)) return;
    patchLike(projectId, { likedByMe: before.likedByMe, count: before.count, pending: false, error: true });
  }
}

export function resetExhibition(): void {
  setState({ status: 'idle', boothId: null, projects: [] });
}

export function __resetExhibitionForTests(): void {
  state = { status: 'idle', boothId: null, projects: [] };
  listeners.clear();
}
