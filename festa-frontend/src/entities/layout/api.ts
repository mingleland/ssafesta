// Booth Layout 5개 endpoint의 실제 fetch 호출 — client.ts의 공용 api()를 그대로 재사용한다
// 출처: specs/005-booth-studio-layout/contracts/layout-api.md §2~§5·§9

import { api } from '../../shared/api/client';
import type {
  DraftGetResponse,
  DraftPutRequest,
  DraftPutResponse,
  PublishResponse,
  PublishedLayout,
  TemplateCatalog,
} from './types';

// 작업본이 없으면 서버가 204를 주고 client.ts의 api()가 이미 undefined로 정규화한다 — 여기서 null로 재포장
export async function getDraft(boothId: number): Promise<DraftGetResponse | null> {
  const result = await api<DraftGetResponse | undefined>(`/api/v1/booths/${boothId}/layouts/draft`);
  return result ?? null;
}

export function putDraft(boothId: number, body: DraftPutRequest): Promise<DraftPutResponse> {
  return api<DraftPutResponse>(`/api/v1/booths/${boothId}/layouts/draft`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export function publish(boothId: number): Promise<PublishResponse> {
  return api<PublishResponse>(`/api/v1/booths/${boothId}/layouts/publish`, { method: 'POST' });
}

export function getPublished(boothId: number): Promise<PublishedLayout> {
  return api<PublishedLayout>(`/api/v1/booths/${boothId}/layouts/published`);
}

export function getTemplates(): Promise<TemplateCatalog> {
  return api<TemplateCatalog>('/api/v1/booth-layout-templates');
}
