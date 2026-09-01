// Project 전시 계약 정본 — Shared Contract Freeze Candidate v0.1 (2026-09-01).
// 필드는 BE ProjectService.ProjectView / VisitorProjectView 실물 전사.
// video 판정 타입(VideoEmbed)은 entities/project/videoEmbed.ts 소유 — shared는 entities 를 import 하지
// 않으므로 여기서 재수출하지 않는다. UI 는 그 파일에서 직접 import 한다(entities 는 UI 하층).
// like 쓰기 API(-135)가 없으므로 canToggle 은 false 고정 — 가짜 토글 금지.

export interface ProjectCardVM {
  projectId: number;
  name: string;
  thumbnailUrl: string | null;
  likeCount: number;
  likedByMe: boolean;
}

export interface ProjectLinksVM {
  deployUrl: string | null;
  gitUrl: string | null;
  portfolioUrl: string | null;
}

export interface ProjectLikeVM {
  count: number;
  likedByMe: boolean;
  canToggle: false;
}

/** PATCH 는 dirty 키만 직렬화한다(BE PresenceField — 키 생략 ≠ null 전송). */
export type ProjectFieldKey =
  | 'name'
  | 'description'
  | 'thumbnailUrl'
  | 'videoUrl'
  | 'deployUrl'
  | 'gitUrl'
  | 'portfolioUrl';
