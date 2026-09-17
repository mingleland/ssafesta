// 부스 배치(layout) 게시 endpoint 4종 — Booth Studio 폐기(-846) 뒤에도 남는 BE 계약을 관리창의
// FE 게시 엔진(features/booth/model/boothPublication.ts)이 그대로 소비한다 (S15P21A604-898).
// 출처: specs/005-booth-studio-layout/contracts/layout-api.md §2~§5, backend BoothLayoutController.
// 편집기 코드를 되살린 것이 아니다 — 여기엔 fetch 래퍼와 응답 타입만 있다.
import { api } from '../../shared/api/client';

/** 배치 문서 한 벌. 필드 이름은 3파트 계약(layout-api.md §1) — 서버 LayoutJson.LayoutDocument 와 같다 */
export interface LayoutObject {
  objectId: string;
  type: string;
  assetCode?: string;
  position: { x: number; y: number; z: number };
  rotationY: number;
  configId?: number;
}

export interface LayoutDocument {
  schemaVersion: number;
  template: string;
  objects: LayoutObject[];
}

/** GET /layouts/draft 200. 작업본이 없으면 서버가 204 → api() 가 undefined 로 정규화 → null */
export interface DraftView extends LayoutDocument {
  boothId: number;
  revision: number;
}

/** PUT /layouts/draft 요청. 최초 저장은 expectedRevision 0, 이후는 GET 이 준 revision 그대로 */
export interface DraftPutRequest extends LayoutDocument {
  expectedRevision: number;
}

export interface PublishResponse {
  boothId: number;
  publishedVersion: number;
  publishedAt: string;
}

/** GET /layouts/published 200. 미게시는 404 LAYOUT_NOT_PUBLISHED */
export interface PublishedLayout extends LayoutDocument {
  boothId: number;
  version: number;
}

export const LAYOUT_REVISION_CONFLICT = 'LAYOUT_REVISION_CONFLICT';
export const LAYOUT_NOT_PUBLISHED = 'LAYOUT_NOT_PUBLISHED';

export async function getDraft(boothId: number): Promise<DraftView | null> {
  const result = await api<DraftView | undefined>(`/api/v1/booths/${boothId}/layouts/draft`);
  return result ?? null;
}

export function putDraft(boothId: number, body: DraftPutRequest): Promise<DraftView> {
  return api<DraftView>(`/api/v1/booths/${boothId}/layouts/draft`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export function publishLayout(boothId: number): Promise<PublishResponse> {
  return api<PublishResponse>(`/api/v1/booths/${boothId}/layouts/publish`, { method: 'POST' });
}

export function getPublishedLayout(boothId: number): Promise<PublishedLayout> {
  return api<PublishedLayout>(`/api/v1/booths/${boothId}/layouts/published`);
}
