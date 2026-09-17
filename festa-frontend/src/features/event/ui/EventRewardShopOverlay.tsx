// 이벤트 상점 Overlay (S15P21A604-599, 실 연동 S15P21A604-842/836).
//
// 즉시교환은 entities/eventShop(GET prizes·POST purchases)에 실제로 연결돼 있다. 응모권은
// entities/raffle을 쓰지만 그 너머는 아직 mock이다(-836 스펙에 추첨 개념 자체가 없음) —
// api.select.ts만 real로 바꾸면 이 파일은 손대지 않고 그대로 실 연동이 된다.
//
// 화폐는 wallet Coin이다. 보유량은 기존 `WalletBadge`를 그대로 쓴다 — member 전용 가드까지 그 안에 있다.
//
// Overlay Bus로 열린다. ESC·배타·Unity 입력 잠금·focus 반환은 이 파일에 없다 — worldScreen이 갖는다.
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isApiError } from '../../../shared/api/client';
import { eventShopApi } from '../../../entities/eventShop/api.select';
import type { EventPrize } from '../../../entities/eventShop/types';
import { raffleApi } from '../../../entities/raffle/api.select';
import type { RafflePrize } from '../../../entities/raffle/types';
import { closeOverlay } from '../../../shared/types/overlay';
import { openVisitorOverlay } from '../../world/model/worldScreen';
import { OverlayCardGrid } from '../../overlay/ui/OverlayCardGrid';
import type { OverlayCard } from '../../overlay/ui/OverlayCardGrid';
import { OverlayFrame, OverlayError, OverlayLoading } from '../../overlay/ui/OverlayFrame';
import { OverlayNotice } from '../../overlay/ui/OverlayNotice';
import { WalletBadge } from '../../wallet/ui/WalletBadge';
import { PurchaseResultDialog } from './PurchaseResultDialog';
import { PurchaseRecipientForm } from './PurchaseRecipientForm';
import type { PurchaseRecipient } from '../../../shared/contracts/purchaseRecipient';
import { drawTimeChipLabel, drawTimeLabel, imageForPrize, imageForRaffle, isSoldOut, stockLabel } from '../model/rewardShop';
import { resolveEventSurveyTarget } from '../model/surveyEntry';
import { showToast } from '../../../shared/ui/toast/toastStore';

interface ResultDialogState {
  title: string;
  itemName: string;
  coinSpent: number;
  /** 응모 완료일 때만 — 언제 추첨하는지. 즉시교환엔 없다(그 자리에서 바로 받는다) */
  note?: string;
}

// 카드를 눌렀을 때 바로 mutate하지 않는다 — 실물 지급이라 받는 자 정보(캠퍼스·조 이름·이름)를
// 먼저 받아야 한다(S15P21A604-842 후속). 그 폼이 뜨는 동안 "무엇을 하려던 참이었는지"를 들고 있는 상태.
type PendingAction = { kind: 'purchase'; prize: EventPrize } | { kind: 'raffle'; raffle: RafflePrize };

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
  RAFFLE_OUT_OF_STOCK: '방금 응모권이 모두 소진됐습니다.',
  RAFFLE_NOT_FOUND: '응모권을 찾을 수 없습니다.',
};

export function EventRewardShopOverlay() {
  const queryClient = useQueryClient();
  const prizesQuery = useQuery({ queryKey: ['event-shop-prizes'], queryFn: eventShopApi.listPrizes });
  const surveyTarget = resolveEventSurveyTarget();

  const [resultDialog, setResultDialog] = useState<ResultDialogState | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);

  const purchase = useMutation({
    mutationFn: ({ prize, recipient }: { prize: EventPrize; recipient: PurchaseRecipient }) =>
      eventShopApi.purchasePrize(prize.prizeId, crypto.randomUUID(), 1, recipient),
    onSuccess: (result) => {
      setPendingAction(null);
      setResultDialog({ title: '교환 완료', itemName: result.prizeName, coinSpent: result.coinSpent });
      void queryClient.invalidateQueries({ queryKey: ['event-shop-prizes'] });
      void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
    },
    onError: (error) => {
      setPendingAction(null);
      const message = isApiError(error) ? PURCHASE_ERROR_LABELS[error.code] ?? error.message : '교환에 실패했습니다.';
      showToast(message, 'error');
      // 재고·잔액이 틀어진 채 남지 않게 최신 상태로 다시 맞춘다
      void queryClient.invalidateQueries({ queryKey: ['event-shop-prizes'] });
      void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
    },
  });

  const enter = useMutation({
    mutationFn: ({ raffle, recipient }: { raffle: RafflePrize; recipient: PurchaseRecipient }) =>
      raffleApi.enterRaffle(raffle.raffleId, crypto.randomUUID(), recipient),
    onSuccess: (result) => {
      setPendingAction(null);
      setResultDialog({
        title: '응모 완료',
        itemName: result.raffleName,
        coinSpent: result.coinSpent,
        note: drawTimeLabel(result.drawAt),
      });
      void queryClient.invalidateQueries({ queryKey: ['event-shop-raffles'] });
      void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
    },
    onError: (error) => {
      setPendingAction(null);
      const message = isApiError(error) ? PURCHASE_ERROR_LABELS[error.code] ?? error.message : '응모에 실패했습니다.';
      showToast(message, 'error');
      void queryClient.invalidateQueries({ queryKey: ['event-shop-raffles'] });
      void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
    },
  });
  const rafflesQuery = useQuery({ queryKey: ['event-shop-raffles'], queryFn: raffleApi.listRaffles });

  function toInstantCard(prize: EventPrize): OverlayCard {
    const soldOut = isSoldOut(prize);
    const imageUrl = imageForPrize(prize.name);
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
          disabled={soldOut}
          onClick={() => setPendingAction({ kind: 'purchase', prize })}
        >
          교환
        </button>
      ),
      disabled: soldOut,
    };
  }

  const instantCards = prizesQuery.isSuccess ? prizesQuery.data.map(toInstantCard) : [];

  function toRaffleCard(raffle: RafflePrize): OverlayCard {
    const soldOut = isSoldOut(raffle);
    const imageUrl = imageForRaffle(raffle.name);
    return {
      id: `raffle-${raffle.raffleId}`,
      media: imageUrl === undefined ? IcRewardFallback : <img src={imageUrl} alt="" />,
      title: raffle.name,
      chips: (
        <>
          <span className="ov-chip ov-chip-coin">{raffle.priceCoin.toLocaleString()} C / 1장</span>
          <span className="ov-chip">{stockLabel(raffle.stock)}</span>
          <span className="ov-chip">{drawTimeChipLabel(raffle.drawAt)}</span>
        </>
      ),
      action: (
        <button
          type="button"
          className="ov-btn ov-btn-raffle"
          disabled={soldOut}
          onClick={() => setPendingAction({ kind: 'raffle', raffle })}
        >
          응모
        </button>
      ),
      disabled: soldOut,
    };
  }

  const raffleCards = rafflesQuery.isSuccess ? rafflesQuery.data.map(toRaffleCard) : [];

  return (
    <OverlayFrame
      title="이벤트 상점"
      subtitle="이벤트 코인으로 한정 수량 경품을 교환하세요"
      size="xl"
      icon={IcGift}
      onClose={closeOverlay}
      headerAction={<WalletBadge />}
    >
      {prizesQuery.isPending && <OverlayLoading label="경품 목록을 불러오는 중..." />}
      {prizesQuery.isError && <OverlayError title="경품 목록을 불러오지 못했습니다" onRetry={() => void prizesQuery.refetch()} />}

      {prizesQuery.isSuccess && (
        <div className="ov-section">
          <div className="ov-section-head">
            <span className="ov-section-title">즉시 교환</span>
            <span className="ov-section-desc">코인으로 바로 받는 상품</span>
          </div>
          {instantCards.length === 0 ? (
            // 빈 목록일 때만 안내판이 얹힐 자리(min-height)가 필요하다 — 상품이 있으면
            // 그 예약 공간이 카드 밑에 빈 틈으로 남는다(S15P21A604-842 QA)
            <div className="ov-grid-wrap">
              <OverlayCardGrid cards={instantCards} label="즉시 교환 경품 목록" />
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
            </div>
          ) : (
            <OverlayCardGrid cards={instantCards} label="즉시 교환 경품 목록" />
          )}
        </div>
      )}

      <div className="ov-section">
        <div className="ov-section-head">
          <span className="ov-section-title">응모권</span>
          <span className="ov-section-desc">코인으로 응모권을 사서 추첨에 참여</span>
        </div>
        {rafflesQuery.isPending && <OverlayLoading label="응모권 목록을 불러오는 중..." />}
        {rafflesQuery.isError && <OverlayError title="응모권 목록을 불러오지 못했습니다" onRetry={() => void rafflesQuery.refetch()} />}
        {rafflesQuery.isSuccess && <OverlayCardGrid cards={raffleCards} label="응모권 목록" />}
      </div>

      {pendingAction !== null && (
        <PurchaseRecipientForm
          actionLabel={pendingAction.kind === 'purchase' ? '교환' : '응모'}
          itemName={pendingAction.kind === 'purchase' ? pendingAction.prize.name : pendingAction.raffle.name}
          priceLabel={
            pendingAction.kind === 'purchase'
              ? `${pendingAction.prize.priceCoin.toLocaleString()} C`
              : `${pendingAction.raffle.priceCoin.toLocaleString()} C / 1장`
          }
          pending={purchase.isPending || enter.isPending}
          onCancel={() => setPendingAction(null)}
          onConfirm={(recipient) => {
            if (pendingAction.kind === 'purchase') purchase.mutate({ prize: pendingAction.prize, recipient });
            else enter.mutate({ raffle: pendingAction.raffle, recipient });
          }}
        />
      )}

      {resultDialog !== null && (
        <PurchaseResultDialog
          title={resultDialog.title}
          itemName={resultDialog.itemName}
          coinSpent={resultDialog.coinSpent}
          note={resultDialog.note}
          onClose={() => setResultDialog(null)}
        />
      )}
    </OverlayFrame>
  );
}
