package com.example.ssafesta.world.booth;

/**
 * 한 슬롯의 부스가 바뀌었다는 신호 (S15P21A604-727, GitLab #193).
 *
 * <p><b>애플리케이션 이벤트이면서 그대로 방송 본문이다.</b> 두 모양을 따로 두면 필드가 갈라지고,
 * 실을 것이 {@code slotId} 하나뿐이라 가를 이유가 없다.
 *
 * <p><b>변경 내용을 싣지 않는다</b>(docs/HDD/부스_변경_신호_계약.md §4). Layout JSON 을 방송하면
 * 부스당 오브젝트 12개(헌법 22조)가 접속자 수만큼 복제되고, 받는 쪽은 어차피 간판·외관·프로젝트를
 * 따로 읽어야 한다. "바뀌었다" 만 알리고 재조회는 기존 REST 가 한다.
 *
 * <p><b>boothId 가 아니라 slotId 다.</b> 월드는 슬롯으로 말한다 — Unity 의
 * {@code BoothLayoutBridge.ReloadBoothSlot} 과 {@code GET /booth-slots/{slotId}/layout/published}
 * 가 둘 다 슬롯 번호를 받는다. 슬롯이 없는 부스(임대 만료·미임대)는 월드에 서 있지 않으므로
 * 신호도 나가지 않는다.
 */
public record BoothChanged(long slotId) { }
