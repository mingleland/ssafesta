// 응모권 real API — 경로·필드는 event-shop(EventShopController) 계약을 본떠 만든 추정치다.
// BE에 이 엔드포인트는 아직 없다(-836 스펙에 추첨 개념 없음, 담당자 확인 결과 추후 추가 예정).
//
// 실 계약이 나오면 이 파일만 경로·필드명에 맞춰 고치면 된다 — 화면(EventRewardShopOverlay)은
// entities/raffle/api.select 너머를 모르므로 건드릴 필요가 없다. 전환은 api.select.ts에서
// `raffleApi = mockApi` 를 `raffleApi = realApi` 로 바꾸는 한 줄이 전부다.
import { api } from '../../shared/api/client';
import type { RaffleEntryResult, RafflePrize } from './types';

export async function listRaffles(): Promise<RafflePrize[]> {
  const res = await api<{ raffles: RafflePrize[] }>('/api/v1/event-shop/raffles');
  return res.raffles;
}

export async function enterRaffle(raffleId: number, idempotencyKey: string): Promise<RaffleEntryResult> {
  return api<RaffleEntryResult>(`/api/v1/event-shop/raffles/${raffleId}/entries`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}
