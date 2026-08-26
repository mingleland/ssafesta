// G-1 Owner 가드(spec 004) — /app/studio/:boothId가 전 member에 열리는 것을 UX 수준에서 막는다.
// 서버 FR-012(403)가 최종 차단이고 이 훅은 안내용이다. Owner 판정이 세션이 아니라 부스별 서버
// 데이터(GET /booths/mine)에 의존하므로 라우트 가드(guard.ts — 순수 세션 함수)에 넣지 않는다.
import { useQuery } from '@tanstack/react-query';
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import type { MyBooth } from '../../../entities/booth/types';

export type OwnerGateStatus = 'loading' | 'owner' | 'not-owner' | 'error';

// 순수 판정 — 네트워크 오류는 차단이 아니라 재시도 안내로 구분한다(무단 차단 방지)
export function judgeOwner(
  myBooth: MyBooth | null | undefined,
  isLoading: boolean,
  isError: boolean,
  boothId: number,
): OwnerGateStatus {
  if (isLoading) return 'loading';
  if (isError) return 'error';
  if (!myBooth || myBooth.boothId !== boothId) return 'not-owner';
  return 'owner';
}

export function useOwnerGate(boothId: number): { status: OwnerGateStatus } {
  const myBoothQuery = useQuery({
    queryKey: ['my-booth'], // SlotListPage와 키 공유 — 캐시 재사용
    queryFn: leaseApi.getMyBooth,
    enabled: Number.isFinite(boothId),
  });
  return {
    status: judgeOwner(myBoothQuery.data, myBoothQuery.isLoading, myBoothQuery.isError, boothId),
  };
}
