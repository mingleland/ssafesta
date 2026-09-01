// Booth 홈페이지 등록 real API — 소유자 전용 (spec 016 FR-001, BoothHomepageService 구현 정본).
// body 는 키 필수다: {} 는 400 FIELD_INVALID, 명시적 null 은 "등록 해제"다(PresenceField — 키 생략과 다름).
import { api } from '../../shared/api/client';
import type { HomepageView } from './types';

export function putHomepage(boothId: number, homepageUrl: string | null): Promise<HomepageView> {
  return api<HomepageView>(`/api/v1/booths/${boothId}/homepage`, {
    method: 'PUT',
    body: JSON.stringify({ homepageUrl }),
  });
}
