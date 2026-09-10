// Overlay 공용 카드 그리드 (S15P21A604-599).
//
// 월드 위 오버레이가 "여러 개를 골라 보는 화면"을 그릴 때 쓰는 하나의 어휘다. 지금 소비처는
// 월드 가이드(무엇을 할 수 있는가)와 이벤트 경품 상점(무엇을 교환할 수 있는가) 둘인데,
// **같은 클래스를 쓰는 것이 요점**이다 — 화면마다 카드 CSS 를 복제하면 그것이 일곱 번째 어휘가 된다.
//
// 빈 목록에서 그리드를 걷지 않는다. 상점이 `PREPARING`(상품 0)일 때 shell 과 격자를 유지한 채
// 그 위에 `OverlayNotice` 를 얹는 것이 이 컴포넌트가 존재하는 이유다 — 상품이 도착하면
// `cards` 만 채우면 되고 레이아웃을 다시 만들지 않는다.
import type { ReactNode } from 'react';

export interface OverlayCard {
  id: string;
  /** 38px 배지 안에 들어가는 그림. 없으면 배지를 그리지 않는다 */
  icon?: ReactNode;
  title: string;
  desc?: string;
  /** `.ov-chip` 묶음 — 상점의 "남은 N개"·"N C" 가 여기 온다 */
  chips?: ReactNode;
  /** 카드 하단 액션. 가이드 카드에는 없고 상점 카드에는 [교환] 이 온다 */
  action?: ReactNode;
  disabled?: boolean;
}

interface Props {
  cards: OverlayCard[];
  /** 스크린 리더용 목록 이름 */
  label: string;
}

export function OverlayCardGrid({ cards, label }: Props) {
  return (
    <ul className="ov-card-grid" aria-label={label}>
      {cards.map((card) => (
        <li key={card.id} className="ov-card" data-disabled={card.disabled === true ? '' : undefined}>
          {card.icon !== undefined && <span className="ov-card-icon">{card.icon}</span>}
          <span className="ov-card-title">{card.title}</span>
          {card.desc !== undefined && <span className="ov-card-desc">{card.desc}</span>}
          {(card.chips !== undefined || card.action !== undefined) && (
            <span className="ov-card-foot">
              {card.chips}
              {card.action}
            </span>
          )}
        </li>
      ))}
    </ul>
  );
}
