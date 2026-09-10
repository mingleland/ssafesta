// 이벤트 상점의 "설문 참여하기" 가 어디로 갈지 (S15P21A604-599).
//
// **가짜 boothId 를 만들지 않는다.** `SurveyOverlay` 는 payload 가 `{ boothId }` 필수이고, 서버가 그
// boothId 로 설문을 resolve 한다(계약 §5, -528). 이벤트 NPC 는 부스에 속하지 않아서 의미 있는 값이
// 없다 — 아무 숫자나 넣으면 없는 부스의 설문을 찾다가 404 로 떨어지고, 그 실패가 사용자에게는
// "설문이 고장났다" 로 보인다.
//
// 그래서 **부족한 것 하나를 이 함수로 좁혀 둔다.** 대상이 정해지면 여기만 바꾸면 되고, 화면·문구·
// 버튼 배치는 지금 확정한 그대로다. 미확정 동안 CTA 는 비활성 + 사유 안내로 뜬다 — 눌러서 실패하는
// 것보다 낫고, 무엇이 없어서 안 되는지가 화면에 남는다.
//
// 필요한 결정: 이벤트 설문이 **어느 부스의 설문인가**(운영 부스 하나를 쓰는지, 부스 없는 설문 조회
// 경로를 BE 가 여는지). 그 답이 오면 아래 상수 하나가 값을 갖는다.
const EVENT_SURVEY_BOOTH_ID: number | null = null;

export function resolveEventSurveyTarget(): { boothId: number } | null {
  return EVENT_SURVEY_BOOTH_ID === null ? null : { boothId: EVENT_SURVEY_BOOTH_ID };
}
