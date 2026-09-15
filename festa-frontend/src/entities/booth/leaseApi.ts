// 부스 슬롯 임대 real API(spec 004) — 연장·환불 endpoint는 계약상 의도적 부재, 만들지 않는다.
// 조기 반납만 2026-09-15 에 열렸다(S15P21A604-735, GitLab #199): 자리는 비우되 코인은 돌려주지
// 않는다. D06 "변심 환불 없음" 은 그대로다.
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

// 조기 반납 — 204 라 본문이 없다. 경로의 slotId 는 확인용이다: 활성 임대는 하나뿐이라 없어도
// 찾을 수 있지만, 화면이 낡아 엉뚱한 자리를 지목하면 서버가 아무것도 하지 않고 404 를 돌려준다.
//
// 실패의 유일한 도메인 코드는 404 ACTIVE_LEASE_NOT_FOUND 이고 세 경우를 하나로 묶는다 —
// 활성 임대 없음 · 내 임대가 다른 자리 · 방금 만료됐거나 이미 반납함. 셋 다 "화면이 낡았다" 는
// 뜻이라 호출부는 오류로 띄우지 않고 재조회 신호로 쓴다(useCancelLease).
export async function cancelMyLease(slotId: number): Promise<void> {
  await api<void>(`/api/v1/booth-slots/${slotId}/leases/mine`, { method: 'DELETE' });
}
