// 부스 반납 mutation (S15P21A604-753, GitLab #199).
//
// **404 를 오류로 다루지 않는 것이 이 모듈의 존재 이유다.** 서버는 실패의 유일한 도메인 코드로
// `404 ACTIVE_LEASE_NOT_FOUND` 하나를 쓰고 세 경우를 묶는다 — 활성 임대 없음 · 내 임대가 다른
// 자리에 있음 · 방금 만료됐거나 이미 반납함. 셋 다 "화면이 낡았다" 는 뜻이고 사용자가 할 일이
// 없다. 재시도한 DELETE 도 여기로 온다. 그래서 배너를 띄우지 않고 **다시 읽는 신호**로만 쓴다.
//
// 잔액은 무효화하지 않는다 — 반납에 환불이 없어(D06) 코인이 움직이지 않는다.
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import type { ApiError } from '../../../shared/api/client';

/** 이 코드가 오면 실패가 아니라 "낡은 화면" 이다 */
export const STALE_VIEW_CODE = 'ACTIVE_LEASE_NOT_FOUND';

export function isStaleViewError(error: unknown): boolean {
  return (error as ApiError | null)?.code === STALE_VIEW_CODE;
}

export function useCancelLease(onDone?: () => void) {
  const queryClient = useQueryClient();

  function refetchBoothViews(): void {
    // 기존 인라인 키를 그대로 쓴다 — 이 저장소에는 query-key factory 가 없고, 여기서 하나
    // 만들면 SlotListPage·BoothManagementOverlay 와 키가 갈린다.
    void queryClient.invalidateQueries({ queryKey: ['booth-slots'] });
    void queryClient.invalidateQueries({ queryKey: ['my-booth'] });
  }

  return useMutation({
    mutationFn: (slotId: number) => leaseApi.cancelMyLease(slotId),
    onSuccess: () => {
      refetchBoothViews();
      onDone?.();
    },
    onError: (error: unknown) => {
      if (!isStaleViewError(error)) return;
      refetchBoothViews();
      onDone?.();
    },
  });
}
