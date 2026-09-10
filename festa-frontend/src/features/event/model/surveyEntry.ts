// 이벤트 상점의 "설문 참여하기" 가 어디로 갈지 (S15P21A604-599 · 대상 확정 -608).
//
// **가짜 boothId 를 만들지 않는다.** 이벤트 설문은 부스에 속하지 않는데, 부스 경로는
// `requireVisitorVisible` 이 부스 존재·ACTIVE 임대·게시 레이아웃 셋을 요구한다. 없는 부스를
// 지어내면 그중 하나가 어긋나는 순간 조용히 404 가 되고, 사용자에게는 "설문이 고장났다" 로 보인다.
//
// 그래서 진입 열쇠를 `surveyKey` 로 둔다. 화면은 부스 설문과 **같은 SurveyOverlay** 이고, 다른 것은
// 이 값 하나다 — `SurveySource` 가 그 차이를 담는다.
//
// key 는 이벤트 하나를 가리킨다. 서버가 `surveys.survey_key` 에 unique 를 걸어 **이벤트당 설문
// 하나**를 보장하므로, 여기서 고를 것이 없고 상수 하나면 된다.
import type { SurveySource } from '../../../shared/contracts/survey';

export const EVENT_SURVEY_KEY = 'SSAFESTA_2026';

export function resolveEventSurveyTarget(): SurveySource {
  return { kind: 'event', surveyKey: EVENT_SURVEY_KEY };
}
