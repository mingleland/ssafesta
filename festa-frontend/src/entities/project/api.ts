// Project real API — 소유자 3종(MEMBER 전용) + 방문자 1종(permitAll).
// 출처: origin/develop backend project/ProjectController.java (구현이 정본, spec 009 §6)
import { api } from '../../shared/api/client';
import type { LikeView, ProjectListView, ProjectPatch, ProjectView, VisitorProjectListView } from './types';

export function createProject(boothId: number, patch: ProjectPatch): Promise<ProjectView> {
  return api<ProjectView>(`/api/v1/booths/${boothId}/projects`, {
    method: 'POST',
    body: JSON.stringify(patch),
  });
}

/** 0개 또는 1개 배열 — 없는 것은 오류가 아니다(아직 안 만들었을 뿐, BE 주석) */
export function getMyProjects(boothId: number): Promise<ProjectListView> {
  return api<ProjectListView>(`/api/v1/booths/${boothId}/projects`);
}

/** 방문자용 — 토큰 없어도 200. 토큰은 likedByMe 판정에만 쓰인다 */
export function getPublishedProjects(boothId: number): Promise<VisitorProjectListView> {
  return api<VisitorProjectListView>(`/api/v1/booths/${boothId}/projects/published`);
}

// 토글 endpoint 가 아니라 멱등 PUT/DELETE 둘이다(#122) — 현재 likedByMe 를 보고 메서드를 고른다.
// 같은 요청을 여러 번 보내도 200·같은 결과라 실패 후 재시도를 그냥 걸어도 된다. 회원 전용(403 MEMBER_ONLY).
export function likeProject(projectId: number): Promise<LikeView> {
  return api<LikeView>(`/api/v1/projects/${projectId}/like`, { method: 'PUT' });
}

export function unlikeProject(projectId: number): Promise<LikeView> {
  return api<LikeView>(`/api/v1/projects/${projectId}/like`, { method: 'DELETE' });
}

/** patch 에는 dirty 키만 담는다 — BE PresenceField(키 생략 ≠ null 전송) */
export function updateProject(projectId: number, patch: ProjectPatch): Promise<ProjectView> {
  return api<ProjectView>(`/api/v1/projects/${projectId}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  });
}
