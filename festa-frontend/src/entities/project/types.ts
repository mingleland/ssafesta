// BE ProjectService 응답 전사 — 구현이 정본 (origin/develop backend project/ProjectService.java).
// 모든 키는 항상 존재하고 값이 없으면 null 이다(record — 계약 §, ProjectView 주석).
import type { ProjectFieldKey } from '../../shared/contracts/project';

export interface ProjectView {
  projectId: number;
  name: string;
  description: string | null;
  thumbnailUrl: string | null;
  videoUrl: string | null;
  deployUrl: string | null;
  gitUrl: string | null;
  portfolioUrl: string | null;
}

/** 방문자 조회 전용 — 토큰 없어도 200, likedByMe 는 토큰이 있을 때만 의미 있다 */
export interface VisitorProjectView extends ProjectView {
  likeCount: number;
  likedByMe: boolean;
}

export interface ProjectListView {
  projects: ProjectView[];
}

export interface VisitorProjectListView {
  projects: VisitorProjectView[];
}

/**
 * POST/PATCH body. BE 는 PresenceField 로 "보낸 키만" 반영한다 — 키 생략은 유지, 명시적
 * null 은 비우기다. 따라서 dirty 키만 담아 직렬화해야 한다(전체 객체 전송 금지).
 */
export type ProjectPatch = Partial<Record<ProjectFieldKey, string | null>>;
