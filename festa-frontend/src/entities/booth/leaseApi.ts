// 부스 슬롯 임대 real API(spec 004) — 연장·취소·환불 endpoint는 계약상 의도적 부재, 만들지 않는다.
// 출처: specs/004-booth-slot-lease/contracts/lease-api.md
import { api } from '../../shared/api/client';
import type { LeaseResponse, MyBooth, SlotView } from './types';

export async function getSlots(): Promise<SlotView[]> {
  return api<SlotView[]>('/api/v1/booth-slots');
}

// durationDays는 1만 허용(계약) — 기간 선택 UI가 없는 이유. 재요청 시 서버가 차감 없이
// 기존 임대를 200으로 반환한다(FR-018) — 클라이언트 멱등성 키 불요.
export async function leaseSlot(slotId: number): Promise<LeaseResponse> {
  return api<LeaseResponse>(`/api/v1/booth-slots/${slotId}/leases`, {
    method: 'POST',
    body: JSON.stringify({ durationDays: 1 }),
  });
}

// 부스 없음은 204 — client.ts가 undefined로 반환하는 것을 null로 정규화한다
// (react-query는 undefined data를 거부하므로 이 ?? null이 유일한 방어선)
export async function getMyBooth(): Promise<MyBooth | null> {
  return (await api<MyBooth | undefined>('/api/v1/booths/mine')) ?? null;
}
