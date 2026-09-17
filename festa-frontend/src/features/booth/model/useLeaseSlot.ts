// 임대 요청 mutation(spec 004 US2) — 성공·실패의 캐시 갱신 규약을 한 곳에 둔다.
// 출처: specs/004-booth-slot-lease/contracts/lease-api.md
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { isApiError } from '../../../shared/api/client';
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import { notifyBoothSlotChanged } from '../../../unity/host/boothLayoutBridge';

export function useLeaseSlot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (slotId: number) => leaseApi.leaseSlot(slotId),
    onSuccess: (_result, slotId) => {
      queryClient.invalidateQueries({ queryKey: ['booth-slots'] });
      queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
      queryClient.invalidateQueries({ queryKey: ['my-booth'] });
      // 임대만으로는 게시본이 생기지 않는다(BE 는 미게시를 LAYOUT_NOT_PUBLISHED 404 로 답한다). 그래도
      // 상주 중인 Unity 가 이 슬롯의 간판·점유 상태를 다시 읽게 알린다 (-644). 월드 밖(mock·미진입)이면
      // 아무 일도 없다: 다음 진입이 읽는다.
      notifyBoothSlotChanged(slotId);
    },
    onError: (error) => {
      if (!isApiError(error)) return;
      // 부족액은 message 문자열에만 있고 파싱은 금지 — 대신 실잔액을 다시 보여준다
      if (error.code === 'INSUFFICIENT_COIN') {
        queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
      }
      // 경합 패배 — 낡은 목록이 원인이므로 서버 상태로 재조회(안내 문구와 짝)
      if (error.code === 'BOOTH_SLOT_ALREADY_LEASED') {
        queryClient.invalidateQueries({ queryKey: ['booth-slots'] });
      }
    },
  });
}
