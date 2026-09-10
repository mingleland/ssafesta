// Overlay 공용 안내 패널 (S15P21A604-599).
//
// "지금은 쓸 수 없다" 를 말하는 자리다 — 준비 중·점검 중·로그인 필요. 화면을 통째로 대체하지 않고
// **본문 위에 얹힌다**: 아래에 있는 그리드·shell 은 그대로 보이고, 조건이 풀리면 이 패널만 사라진다.
// 그래서 상품이 도착했을 때 상점을 다시 만들 필요가 없다.
//
// `festa-overlay-state`(OverlayEmpty/Error)와 다른 물건이다. 그쪽은 본문 자리를 **차지하는** 표현이라
// 뒤에 아무것도 없을 때 쓰고, 이쪽은 뒤에 유지할 것이 있을 때 쓴다.
//
// 프레임 밖 raw div 로 만들지 않는다 — 그러면 배경 클릭 닫기·focus 소유가 프레임과 갈라진다.
// 부모에 `.ov-grid-wrap`(position: relative)을 두고 그 안에서 절대 배치한다.
import type { ReactNode } from 'react';

interface Props {
  title: string;
  message?: string;
  /** 버튼 한 개를 기대한다. 없으면 안내만 그린다 */
  action?: ReactNode;
  /** 버튼을 쓸 수 없을 때의 사유 — `.ov-note` 로 버튼 아래 붙는다 */
  hint?: string;
}

export function OverlayNotice({ title, message, action, hint }: Props) {
  return (
    <div className="ov-notice" role="status">
      <strong className="ov-notice-title">{title}</strong>
      {message !== undefined && <p className="ov-notice-message">{message}</p>}
      {action}
      {hint !== undefined && <p className="ov-note">{hint}</p>}
    </div>
  );
}
