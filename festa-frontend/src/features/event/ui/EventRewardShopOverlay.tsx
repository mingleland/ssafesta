// 이벤트 상점 Overlay (S15P21A604-599, 실 연동 S15P21A604-842/836).
//
// 즉시교환은 entities/eventShop(GET prizes·POST purchases)에 실제로 연결돼 있다. 응모권은
// 대응 BE가 없다(-836 스펙에 추첨 개념 자체가 없음) — 정적 안내 카드만 두고 버튼을 비활성으로
// 둔다. 없는 API를 mock으로 있는 척 흉내내면 코인이 빠졌는데 아무 일도 안 일어나는 화면이 된다.
//
// 화폐는 wallet Coin이다. 보유량은 기존 `WalletBadge`를 그대로 쓴다 — member 전용 가드까지 그 안에 있다.
//
// Overlay Bus로 열린다. ESC·배타·Unity 입력 잠금·focus 반환은 이 파일에 없다 — worldScreen이 갖는다.
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isApiError } from '../../../shared/api/client';
import { eventShopApi } from '../../../entities/eventShop/api.select';
import type { EventPrize } from '../../../entities/eventShop/types';
import { closeOverlay } from '../../../shared/types/overlay';
import { openVisitorOverlay } from '../../world/model/worldScreen';
import { OverlayCardGrid } from '../../overlay/ui/OverlayCardGrid';
import type { OverlayCard } from '../../overlay/ui/OverlayCardGrid';
import { OverlayFrame, OverlayError, OverlayLoading } from '../../overlay/ui/OverlayFrame';
import { OverlayNotice } from '../../overlay/ui/OverlayNotice';
import { WalletBadge } from '../../wallet/ui/WalletBadge';
import { RAFFLE_DISPLAY_ITEMS, imageForPrize, isSoldOut, stockLabel } from '../model/rewardShop';
import { resolveEventSurveyTarget } from '../model/surveyEntry';
import { showToast } from '../../../shared/ui/toast/toastStore';

const IcGift = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 11h16v9H4zM3 7h18v4H3zM12 7v13" />
    <path d="M12 7S9.5 3.5 7.8 4.4C6.4 5.1 6.9 7 8.6 7Zm0 0s2.5-3.5 4.2-2.6C17.6 5.1 17.1 7 15.4 7Z" />
  </svg>
);

const IcRewardFallback = (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 11h16v9H4zM3 7h18v4H3zM12 7v13" />
  </svg>
);

// 서버 코드 → 화면 문구. 모르는 코드는 서버 message 그대로 보여준다(SC-005와 같은 이유 — 숨기지 않는다).
const PURCHASE_ERROR_LABELS: Record<string, string> = {
  EVENT_PRIZE_OUT_OF_STOCK: '방금 재고가 소진됐습니다.',
  EVENT_PRIZE_INACTIVE: '판매가 중단된 경품입니다.',
  INSUFFICIENT_COIN: '코인이 부족합니다.',
  EVENT_PRIZE_NOT_FOUND: '경품을 찾을 수 없습니다.',
};

export function EventRewardShopOverlay() {
  const queryClient = useQueryClient();
  const prizesQuery = useQuery({ queryKey: ['event-shop-prizes'], queryFn: eventShopApi.listPrizes });
  const surveyTarget = resolveEventSurveyTarget();

  const purchase = useMutation({
    mutationFn: (prize: EventPrize) => eventShopApi.purchasePrize(prize.prizeId, crypto.randomUUID()),
    onSuccess: (result) => {
      showToast(`${result.prizeName} 교환 완료 — ${result.coinSpent.toLocaleString()}C 사용`, 'success');
      void queryClient.invalidateQueries({ queryKey: ['event-shop-prizes'] });
      void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
    },
    onError: (error) => {
      const message = isApiError(error) ? PURCHASE_ERROR_LABELS[error.code] ?? error.message : '교환에 실패했습니다.';
      showToast(message, 'error');
      // 재고·잔액이 틀어진 채 남지 않게 최신 상태로 다시 맞춘다
      void queryClient.invalidateQueries({ queryKey: ['event-shop-prizes'] });
      void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
    },
  });

  const [pendingPrizeId, setPendingPrizeId] = useState<number | null>(null);

  function toInstantCard(prize: EventPrize): OverlayCard {
    const soldOut = isSoldOut(prize);
    const imageUrl = imageForPrize(prize.name);
    const isPending = purchase.isPending && pendingPrizeId === prize.prizeId;
    return {
      id: `prize-${prize.prizeId}`,
      media: imageUrl === undefined ? IcRewardFallback : <img src={imageUrl} alt="" />,
      title: prize.name,
      chips: (
        <>
          <span className="ov-chip ov-chip-coin">{prize.priceCoin.toLocaleString()} C</span>
          <span className="ov-chip">{stockLabel(prize.stock)}</span>
        </>
      ),
      action: (
        <button
          type="button"
          className="ov-btn ov-btn-primary"
          disabled={soldOut || purchase.isPending}
          onClick={() => {
            setPendingPrizeId(prize.prizeId);
            purchase.mutate(prize);
          }}
        >
          {isPending ? '교환 중...' : '교환'}
        </button>
      ),
      disabled: soldOut,
    };
  }

  const instantCards = prizesQuery.isSuccess ? prizesQuery.data.map(toInstantCard) : [];

  const raffleCards: OverlayCard[] = RAFFLE_DISPLAY_ITEMS.map((item) => ({
    id: item.id,
    media: <img src={item.imageUrl} alt="" />,
    title: item.name,
    chips: (
      <>
        <span className="ov-chip ov-chip-coin">{item.priceCoin.toLocaleString()} C / 1장</span>
        <span className="ov-chip">응모 준비 중</span>
      </>
    ),
    action: (
      <button type="button" className="ov-btn ov-btn-raffle" disabled title="응모권 기능은 백엔드 연동 후 열립니다">
        응모
      </button>
    ),
    disabled: true,
  }));

  return (
    <OverlayFrame
      title="이벤트 상점"
      subtitle="이벤트 코인으로 한정 수량 경품을 교환하세요"
      size="xl"
      icon={IcGift}
      onClose={closeOverlay}
      status={<WalletBadge />}
    >
      {prizesQuery.isPending && <OverlayLoading label="경품 목록을 불러오는 중..." />}
      {prizesQuery.isError && <OverlayError title="경품 목록을 불러오지 못했습니다" onRetry={() => void prizesQuery.refetch()} />}

      {prizesQuery.isSuccess && (
        <div className="ov-section">
          <div className="ov-section-head">
            <span className="ov-section-title">즉시 교환</span>
            <span className="ov-section-desc">코인으로 바로 받는 상품</span>
          </div>
          {/* 그리드는 빈 목록에서도 걷지 않는다 — 준비 중 안내가 그 위에 얹히는 구조라서다 */}
          <div className="ov-grid-wrap">
            <OverlayCardGrid cards={instantCards} label="즉시 교환 경품 목록" />
            {instantCards.length === 0 && (
              <OverlayNotice
                title="경품 상점 준비 중"
                message="설문 참여 시 추첨을 통해 경품을 드립니다."
                action={
                  // 부스 설문과 같은 오버레이로 간다 — 다른 것은 payload의 source 하나다 (-608)
                  <button type="button" className="ov-btn ov-btn-primary" onClick={() => openVisitorOverlay('SURVEY', surveyTarget)}>
                    설문 참여하기
                  </button>
                }
              />
            )}
          </div>
        </div>
      )}

      <div className="ov-section">
        <div className="ov-section-head">
          <span className="ov-section-title">응모권</span>
          <span className="ov-section-desc">코인으로 응모권을 사서 추첨에 참여 — 준비 중</span>
        </div>
        <OverlayCardGrid cards={raffleCards} label="응모권 목록" />
      </div>
    </OverlayFrame>
  );
}
