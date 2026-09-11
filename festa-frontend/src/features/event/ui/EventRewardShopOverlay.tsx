// 이벤트 경품 상점 Overlay (S15P21A604-599).
//
// **임시 화면이 아니다.** 지금은 경품이 없지만 구조는 최종형이다 — 같은 shell·같은 그리드·같은 카드에
// `items` 만 채우면 `READY` 가 된다. 준비 중 안내는 그 위에 얹히는 `OverlayNotice` 하나이고, 상품이
// 도착하면 그것만 사라진다.
//
// 화폐는 wallet Coin 이다(rewardShop.ts 주석). 보유량은 기존 `WalletBadge` 를 그대로 쓴다 —
// member 전용 가드까지 그 안에 있다.
//
// Overlay Bus 로 열린다. ESC·배타·Unity 입력 잠금·focus 반환은 이 파일에 없다 — worldScreen 이 갖는다.
import { closeOverlay } from '../../../shared/types/overlay';
import { openVisitorOverlay } from '../../world/model/worldScreen';
import { OverlayCardGrid } from '../../overlay/ui/OverlayCardGrid';
import type { OverlayCard } from '../../overlay/ui/OverlayCardGrid';
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import { OverlayNotice } from '../../overlay/ui/OverlayNotice';
import { WalletBadge } from '../../wallet/ui/WalletBadge';
import { getRewardShopState, remainingLabel } from '../model/rewardShop';
import type { RewardItem } from '../model/rewardShop';
import { resolveEventSurveyTarget } from '../model/surveyEntry';

const IcGift = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 11h16v9H4zM3 7h18v4H3zM12 7v13" />
    <path d="M12 7S9.5 3.5 7.8 4.4C6.4 5.1 6.9 7 8.6 7Zm0 0s2.5-3.5 4.2-2.6C17.6 5.1 17.1 7 15.4 7Z" />
  </svg>
);

const IcRewardFallback = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 11h16v9H4zM3 7h18v4H3zM12 7v13" />
  </svg>
);

/** 상품 하나를 카드로. `READY` 가 되면 이 함수가 그리는 것이 화면의 전부다 */
function toCard(item: RewardItem): OverlayCard {
  const soldOut = item.remaining === 0;
  return {
    id: item.id,
    icon: item.imageUrl === undefined
      ? IcRewardFallback
      : <img className="ov-card-art" src={item.imageUrl} alt="" />,
    title: item.name,
    chips: (
      <>
        <span className="ov-chip">{remainingLabel(item)}</span>
        <span className="ov-chip">{item.priceCoin.toLocaleString()} C</span>
      </>
    ),
    action: (
      <button type="button" className="ov-btn ov-btn-primary" disabled={soldOut}>
        교환
      </button>
    ),
    disabled: soldOut,
  };
}

export function EventRewardShopOverlay() {
  const { phase, items } = getRewardShopState();
  const surveyTarget = resolveEventSurveyTarget();

  return (
    <OverlayFrame
      title="이벤트 경품 상점"
      subtitle="이벤트 코인으로 한정 수량 경품을 교환하세요"
      size="l"
      icon={IcGift}
      onClose={closeOverlay}
      status={<WalletBadge />}
    >
      {/* 그리드는 어느 상태에서도 걷지 않는다 — 준비 중 안내가 그 위에 얹히는 구조라서다 */}
      <div className="ov-grid-wrap">
        <OverlayCardGrid cards={items.map(toCard)} label="경품 목록" />
        {phase === 'PREPARING' && (
          <OverlayNotice
            title="경품 상점 준비 중"
            message="설문 참여 시 추첨을 통해 경품을 드립니다."
            action={
              // 부스 설문과 같은 오버레이로 간다 — 다른 것은 payload 의 source 하나다 (-608)
              <button type="button" className="ov-btn ov-btn-primary" onClick={() => openVisitorOverlay('SURVEY', surveyTarget)}>
                설문 참여하기
              </button>
            }
          />
        )}
      </div>
    </OverlayFrame>
  );
}
