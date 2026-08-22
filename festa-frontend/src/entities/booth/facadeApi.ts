// Booth facade 2개 endpoint의 실제 fetch 호출 — client.ts의 공용 api()를 그대로 재사용한다
// 출처: specs/005-booth-studio-layout/contracts/layout-api.md §6·§7

import { api } from '../../shared/api/client';
import type { BoothDetail, FacadePutRequest, BoothFacade } from './types';

export function getBooth(boothId: number): Promise<BoothDetail> {
  return api<BoothDetail>(`/api/v1/booths/${boothId}`);
}

export function putFacade(boothId: number, body: FacadePutRequest): Promise<BoothFacade> {
  return api<BoothFacade>(`/api/v1/booths/${boothId}/facade`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}
