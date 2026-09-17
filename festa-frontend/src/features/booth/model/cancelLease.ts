// 부스 반납 mutation (S15P21A604-753, GitLab #199).
//
// **404 를 오류로 다루지 않는 것이 이 모듈의 존재 이유다.** 서버는 실패의 유일한 도메인 코드로
// `404 ACTIVE_LEASE_NOT_FOUND` 하나를 쓰고 세 경우를 묶는다 — 활성 임대 없음 · 내 임대가 다른
// 자리에 있음 · 방금 만료됐거나 이미 반납함. 셋 다 "화면이 낡았다" 는 뜻이고 사용자가 할 일이
// 없다. 재시도한 DELETE 도 여기로 온다. 그래서 배너를 띄우지 않고 **다시 읽는 신호**로만 쓴다.
//
// 잔액은 무효화하지 않는다 — 반납에 환불이 없어(D06) 코인이 움직이지 않는다.
//
// **성공 뒤에는 상주 월드에도 알린다 (#199 게임 파트 요청, 2026-09-16).** 게임 파트가 `-730` 후속으로
// "게시본 없음" 을 받으면 부스 내부를 비우도록 고쳤는데, FE 가 재조회를 안 알리면 그 경로가 돌지
// 않는다 — 반납한 뒤에도 다른 접속자 화면에는 직원·조명·포털이 켜진 채 남고 간판도 그대로다.
// facade 저장(-786)·게시(-644)에는 이미 걸려 있던 seam 이고 반납만 빠져 있었다.
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import { notifyBoothSlotChanged } from '../../../unity/host/boothLayoutBridge';
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
    onSuccess: (_data, slotId) => {
      refetchBoothViews();
      // 캐시가 아니라 mutation 변수의 slotId 를 쓴다 — 바로 위에서 `['my-booth']` 를 무효화했고,
      // 애초에 반납한 슬롯이 무엇인지는 호출부가 이미 알고 있다.
      notifyBoothSlotChanged(slotId);
      onDone?.();
    },
    onError: (error: unknown) => {
      if (!isStaleViewError(error)) return;
      // 여기서는 월드에 알리지 않는다. 404 는 반납 사건이 아니라 "화면이 낡았다" 는 신호라
      // 이 자리의 slotId 가 이미 남의 것이거나 만료된 값일 수 있다.
      refetchBoothViews();
      onDone?.();
    },
  });
}
