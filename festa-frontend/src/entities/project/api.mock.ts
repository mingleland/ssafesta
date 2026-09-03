// Project mock — real 과 같은 시그니처. 방문자 시나리오: boothId 1 = 전시 1건, 2 = 0건(empty),
// 99 = 오류. 테스트가 PATCH 직렬화를 검증할 수 있게 마지막 patch 를 노출한다.
import type { ApiError } from '../../shared/api/client';
import type { LikeView, ProjectListView, ProjectPatch, ProjectView, VisitorProjectListView } from './types';

const sample: ProjectView = {
  projectId: 10,
  name: 'SSAFY FESTA 메타버스',
  description: '부스에서 만나는 팀 프로젝트 전시',
  thumbnailUrl: null,
  videoUrl: 'https://youtu.be/dQw4w9WgXcQ',
  deployUrl: 'https://festa.example.com',
  gitUrl: null,
  portfolioUrl: null,
};

let owned: ProjectView | null = { ...sample };
let lastUpdatePatch: ProjectPatch | null = null;
// 좋아요 상태 — 멱등 PUT/DELETE 재현(#122). 이미 누른 PUT 도 200·count 불변
let likeCount = 7;
let likedByMe = false;
let failNextLike = false;

function apiError(code: string, message: string): ApiError {
  return { code, message, errors: [], warnings: [] };
}

const EMPTY_PROJECT: ProjectView = {
  projectId: 0,
  name: '',
  description: null,
  thumbnailUrl: null,
  videoUrl: null,
  deployUrl: null,
  gitUrl: null,
  portfolioUrl: null,
};

export async function createProject(_boothId: number, patch: ProjectPatch): Promise<ProjectView> {
  // real BE 는 미전송 키를 null 로 만든다 — sample 잔재값을 유령처럼 남기지 않는다 (-377)
  owned = { ...EMPTY_PROJECT, projectId: 11, ...normalize(patch) };
  return { ...owned };
}

export async function getMyProjects(_boothId: number): Promise<ProjectListView> {
  return { projects: owned ? [{ ...owned }] : [] };
}

export async function getPublishedProjects(boothId: number): Promise<VisitorProjectListView> {
  if (boothId === 99) throw apiError('UNKNOWN', '일시적인 오류입니다.');
  if (boothId === 2) return { projects: [] };
  return { projects: [{ ...sample, likeCount, likedByMe }] };
}

export async function likeProject(projectId: number): Promise<LikeView> {
  if (failNextLike) {
    failNextLike = false;
    throw apiError('UNKNOWN', '일시적인 오류입니다.');
  }
  if (projectId !== sample.projectId) throw apiError('PROJECT_NOT_FOUND', '프로젝트가 없습니다.');
  if (!likedByMe) {
    likedByMe = true;
    likeCount += 1;
  }
  return { likeCount, likedByMe: true };
}

export async function unlikeProject(projectId: number): Promise<LikeView> {
  if (failNextLike) {
    failNextLike = false;
    throw apiError('UNKNOWN', '일시적인 오류입니다.');
  }
  if (projectId !== sample.projectId) throw apiError('PROJECT_NOT_FOUND', '프로젝트가 없습니다.');
  if (likedByMe) {
    likedByMe = false;
    likeCount -= 1;
  }
  return { likeCount, likedByMe: false };
}

export function __failNextLikeForTests(): void {
  failNextLike = true;
}

export async function updateProject(projectId: number, patch: ProjectPatch): Promise<ProjectView> {
  if (!owned || owned.projectId !== projectId) throw apiError('PROJECT_NOT_FOUND', '프로젝트가 없습니다.');
  if (patch.name === 'FAIL') throw apiError('UNKNOWN', '일시적인 오류입니다.'); // 저장 실패 시나리오
  lastUpdatePatch = { ...patch };
  owned = { ...owned, ...normalize(patch) };
  return { ...owned };
}

// PresenceField 동작 재현 — 보낸 키만 반영, null 은 비우기
function normalize(patch: ProjectPatch): Partial<ProjectView> {
  return Object.fromEntries(Object.entries(patch)) as Partial<ProjectView>;
}

export function __lastUpdatePatchForTests(): ProjectPatch | null {
  return lastUpdatePatch;
}

export function __resetProjectMockForTests(): void {
  owned = { ...sample };
  lastUpdatePatch = null;
  likeCount = 7;
  likedByMe = false;
  failNextLike = false;
}
