// Project 전시 계약 정본 — Shared Contract Freeze Candidate v0.1 (2026-09-01).
// 필드는 BE ProjectService.ProjectView / VisitorProjectView 실물 전사.
// video 판정 타입(VideoEmbed)은 entities/project/videoEmbed.ts 소유 — shared는 entities 를 import 하지
// 않으므로 여기서 재수출하지 않는다. UI 는 그 파일에서 직접 import 한다(entities 는 UI 하층).
// like 쓰기는 -135(#122, 2026-09-02 develop 도달) — 회원만(403 MEMBER_ONLY). canToggle 은 세션이 회원일 때만 true.

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
  /** 회원 세션에서만 true — 게스트·비로그인은 서버가 403 MEMBER_ONLY 라 버튼을 열지 않는다 */
  canToggle: boolean;
  /** 요청 in-flight — 중복 클릭 차단. 낙관적 반영은 이 동안 유지된다 */
  pending: boolean;
  /** 마지막 토글이 실패해 되돌렸다(#122: PUT/DELETE 는 멱등이라 재시도 안전) */
  error: boolean;
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
