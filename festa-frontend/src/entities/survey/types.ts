// Survey wire DTO — 서버가 실제로 주고받는 모양 그대로다 (specs/010-survey/contracts/survey-api.md).
// VM 으로의 변환은 전부 mapper.ts 가 한다. 이 파일에는 로직이 없다.
//
// **wire id 는 전부 number 다** (§2 `"surveyId": 12` · `"questionId": 101` · `"optionId": 1001`).
// VM 쪽 id 규칙과 그 차이는 mapper.ts 머리에 적었다.

/** 계약 §1 "문항 유형" — 저장소 전역이 대문자 enum 이다 */
export type SurveyWireQuestionType =
  | 'SINGLE_CHOICE'
  | 'MULTIPLE_CHOICE'
  | 'RATING'
  | 'SHORT_TEXT'
  | 'LONG_TEXT'
  | 'APPLICATION';

// `type` 을 union 이 아니라 string 으로 받는다 — 서버가 계약 밖 값을 보내는 경우를 타입이 아니라
// **런타임에서 드러내기 위해서**다. union 으로 받으면 컴파일러가 "그럴 리 없다" 고 해 주는 대신
// 실제로 그런 값이 왔을 때 조용히 통과한다. mapper 가 판정하고 모르면 던진다.
export interface SurveyQuestionWire {
  questionId: number;
  type: string;
  prompt: string;
  required: boolean;
  /** 0부터 연속. 배열 순서와 항상 같다 (§2) — FE 는 배열 순서를 쓴다 */
  order: number;
  /** 선택형이 아니면 빈 배열 */
  options: { optionId: number; label: string }[];
  /** 별점이 아니면 null */
  scale: { min: number; max: number } | null;
}

/** §2 Survey 표현 — §3 편집자 조회의 200 본문 */
export interface SurveyWire {
  surveyId: number;
  boothId: number;
  title: string;
  description: string | null;
  rewardCoin: number;
  closesAt: string | null;
  /** 서버 판정 (`closesAt != null && closesAt <= now`). FE 는 표시만 한다 */
  closed: boolean;
  responseCount: number;
  questions: SurveyQuestionWire[];
}

/** §5 방문자 조회 */
export interface SurveyRunWire {
  surveyId: number;
  closed: boolean;
  /** 게스트에게 "보상이 있어 회원만 참여할 수 있다" 를 제출 실패 전에 알리기 위해 실린다 */
  rewardCoin: number;
  questions: SurveyQuestionWire[];
}

/**
 * 이벤트 설문 run — 부스 경로(`SurveyRunWire`)와 같은 문항 모양에 세 가지가 더 붙는다
 * (S15P21A604-608).
 *
 *   surveyKey   진입 열쇠. 응답에 되싣는 이유는 늦은 응답 가드가 요청과 대조하기 위해서다
 *   memberOnly  보상 유무와 무관하게 회원 전용이다. 이벤트 설문은 추첨 때문에 참여자를 특정해야 한다
 *   responded   이미 참여했으면 그 응답. 두 번째 조회로 알아내지 않는다
 */
export interface EventSurveyRunWire {
  surveyKey: string;
  surveyId: number;
  closed: boolean;
  rewardCoin: number;
  memberOnly: boolean;
  responded: { responseId: number; submittedAt: string } | null;
  questions: SurveyQuestionWire[];
}

/** §6 제출 201 */
export interface SurveySubmitWire {
  responseId: number;
  /** 실제 지급된 코인. 보상 없는 설문·게스트 무보상 제출은 0 이고 **키는 항상 있다** */
  rewardedCoin: number;
}

/** §7 문항별 집계 — 유형과 무관하게 counts·average·distribution 이 항상 있다 */
export interface SurveyAggregateWire {
  questionId: number;
  type: string;
  /** 그 문항에 답한 응답 수. totalResponses 와 다를 수 있다 — 비율의 분모는 이것이다 */
  answeredCount: number;
  counts: { optionId: number; label: string; count: number }[];
  /** 응답 0건이면 null */
  average: number | null;
  distribution: { value: number; count: number }[];
}

/** §7·§8 주관식 페이지 — 전역 page/size 규약 */
export interface SurveyTextPageWire {
  content: { responseId: number; questionId: number; text: string }[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** §7 결과 */
export interface SurveyResultWire {
  surveyId: number;
  totalResponses: number;
  firstRespondedAt: string | null;
  lastRespondedAt: string | null;
  perQuestion: SurveyAggregateWire[];
  textAnswers: SurveyTextPageWire;
}

// §4 PUT 본문. description·rewardCoin·closesAt 은 **키 존재 여부로 판정**되므로
// FE 가 그 값을 편집하지 않는 한 키를 아예 싣지 않는다 — 실으면 기존 값이 지워진다.
export interface SurveySaveQuestionWire {
  type: SurveyWireQuestionType;
  prompt: string;
  required: boolean;
  /** 선택형만. 선택형이 아니면 키 자체가 없어야 한다 */
  options?: { label: string }[];
  /** RATING 만. RATING 이 아니면 키 자체가 없어야 한다 */
  scale?: { min: number; max: number };
}

export interface SurveySaveWire {
  title: string;
  questions: SurveySaveQuestionWire[];
}

/** §6 제출 본문 — 유형에 맞는 키 하나만 채운다 */
export type SurveyAnswerWire =
  | { questionId: number; selectedOptionIds: number[] }
  | { questionId: number; rating: number }
  | { questionId: number; text: string };

export interface SurveySubmitBodyWire {
  answers: SurveyAnswerWire[];
}
