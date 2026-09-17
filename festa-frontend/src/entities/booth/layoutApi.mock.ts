// layoutApi 의 메모리 mock (S15P21A604-898). VITE_USE_MOCK=true 에서 관리창 게시 흐름을 서버 없이
// 돌린다. 계약 재현이 목적이라 revision 검사·미게시 404·publishedLayoutVersion 연동을 그대로 흉내 낸다.
import type { ApiError } from '../../shared/api/client';
import type { DraftPutRequest, DraftView, PublishResponse, PublishedLayout } from './layoutApi';
import { LAYOUT_NOT_PUBLISHED, LAYOUT_REVISION_CONFLICT } from './layoutApi';

const drafts = new Map<number, DraftView>();
const published = new Map<number, PublishedLayout>();

function apiError(code: string, message: string, status: number): ApiError {
  return { code, message, status, errors: [], warnings: [] };
}

/** facadeApi.mock.getBooth 가 publishedLayoutVersion 을 여기서 읽는다 */
export function getMockPublishedVersion(boothId: number): number | null {
  return published.get(boothId)?.version ?? null;
}

export async function getDraft(boothId: number): Promise<DraftView | null> {
  return drafts.get(boothId) ?? null;
}

export async function putDraft(boothId: number, body: DraftPutRequest): Promise<DraftView> {
  const current = drafts.get(boothId);
  const serverRevision = current?.revision ?? 0;
  if (body.expectedRevision !== serverRevision) {
    throw apiError(LAYOUT_REVISION_CONFLICT, '다른 편집자가 먼저 저장했습니다.', 409);
  }
  const saved: DraftView = {
    boothId,
    revision: serverRevision + 1,
    schemaVersion: body.schemaVersion,
    template: body.template,
    objects: body.objects,
  };
  drafts.set(boothId, saved);
  return saved;
}

export async function publishLayout(boothId: number): Promise<PublishResponse> {
  const draft = drafts.get(boothId);
  if (!draft) throw apiError('LAYOUT_VALIDATION_FAILED', '공개할 배치가 없습니다.', 400);
  const version = (published.get(boothId)?.version ?? 0) + 1;
  published.set(boothId, {
    boothId,
    version,
    schemaVersion: draft.schemaVersion,
    template: draft.template,
    objects: draft.objects,
  });
  return { boothId, publishedVersion: version, publishedAt: new Date().toISOString() };
}

export async function getPublishedLayout(boothId: number): Promise<PublishedLayout> {
  const layout = published.get(boothId);
  if (!layout) throw apiError(LAYOUT_NOT_PUBLISHED, '공개된 배치가 없습니다.', 404);
  return layout;
}

export function __resetLayoutMockForTests(): void {
  drafts.clear();
  published.clear();
}
