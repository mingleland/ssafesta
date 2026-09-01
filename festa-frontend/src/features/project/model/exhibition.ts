// 방문자 전시 상태 기계 — openOverlay('PROJECT', { boothId }) intent 이후의 데이터 흐름 (S15P21A604-134).
// Unity BOOTH_PROJECT_INTERACT(-343)는 미구현 — mock 경로는 openOverlay 직접 호출이 진입점이고,
// 계약 확정 시 dispatcher 에 case 하나가 추가될 뿐 이 모델은 불변이다.
import { useSyncExternalStore } from 'react';
import { projectApi } from '../../../entities/project/api.select';
import { parseVideoEmbed, type VideoEmbed } from '../../../entities/project/videoEmbed';
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
      // -135(좋아요 쓰기 API) 부재 — 표시 전용, 가짜 토글 금지
      like: { count: p.likeCount, likedByMe: p.likedByMe, canToggle: false as const },
    }));
    setState({ status: projects.length === 0 ? 'empty' : 'ready', boothId, projects });
  } catch {
    if (state.boothId !== boothId) return;
    setState({ status: 'error', boothId, projects: [] });
  }
}

export function resetExhibition(): void {
  setState({ status: 'idle', boothId: null, projects: [] });
}

export function __resetExhibitionForTests(): void {
  state = { status: 'idle', boothId: null, projects: [] };
  listeners.clear();
}
