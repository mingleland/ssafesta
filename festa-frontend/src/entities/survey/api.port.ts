// Survey Port — FE 가 필요로 하는 데이터 요구의 표현이다 (S15P21A604-368 · 실 계약 반영 -528).
//
// BE 계약이 확정되고 구현이 develop 에 도달했다 (specs/010-survey/contracts/survey-api.md,
// GitLab #133). 이 Port 는 그 계약을 **그대로 옮긴 것이 아니라** FE 가 필요로 하는 모양이고,
// wire 와의 차이는 mapper.ts 가 흡수한다.
//
// ## 왜 boothId 로 들어오는가
//
// 편집·방문자 경로의 wire 는 전부 부스 기준이다(/booths/{boothId}/survey…). 설문 자체의 id 는
// **run 응답이 처음 알려 준다** — 그래서 FE 가 쓰던 합성 id booth-{n} 이 사라졌다. 제출·결과·
// 주관식 페이지는 그렇게 받은 surveyId 로 부른다.
//
// ## id 가 두 종류인 이유
//
//   surveyId          number. 서버만 만든다 — FE 가 채번할 일이 없어 그대로 number 로 둔다
//   문항·선택지 id     string. Builder 가 서버 id 가 생기기 전에 q-1·o-1 을 스스로 만들고,
//                     PUT 은 애초에 id 를 받지 않는다. run 경로에서는 서버 id 를 String() 으로 담는다
//
// 두 공간이 섞이면 제출이 조용히 깨지므로 mapper 의 toWireAnswers 가 그 자리에서 던진다.
import type {
  SurveyAnswerValue,
  SurveyDraftVM,
  SurveyQuestionAggregateVM,
  SurveyQuestionVM,
} from '../../shared/contracts/survey';

export interface SurveyRunSnapshot {
  /** 이후 제출·결과 조회에 쓰는 서버 id. 여기서 처음 알게 된다 */
  surveyId: number;
  /** closed = 마감 — 질문이 있어도 제출할 수 없다 */
  status: 'open' | 'closed';
  /**
   * 이 설문의 보상 코인. 0 이면 보상 없음.
   * **0 보다 크면 게스트는 제출할 수 없다**(403 MEMBER_ONLY) — 실패하기 전에 알리라고 서버가 싣는다.
   */
  rewardCoin: number;
  questions: SurveyQuestionVM[];
}

export interface SurveyTextAnswerPage {
  /**
   * 주관식 답변 한 페이지.
   *
   * **`questionId` 는 서버 wire id 를 담은 string 이다** — `String(서버 questionId)` 이고 run 경로
   * 문항 id 와 **같은 공간**이다. Builder 가 로컬로 채번하는 `q-1` 과는 무관하므로, 이 값으로
   * run 경로 문항과 대조하는 것은 유효하고 draft 경로 id 와 비교하는 것은 항상 틀린다.
   *
   * text 만 남기면 텍스트 문항이 2개 이상인 설문에서 **어느 질문의 답인지 복구할 수 없다**
   * (GitLab #133, 2026-09-08 BE 지적).
   */
  items: { questionId: string; text: string }[];
  page: number;
  hasNext: boolean;
}

export interface SurveyResultSnapshot {
  surveyId: number;
  /**
   * 최초·최근 응답 시각. **wire 의 ISO 문자열 원형 그대로 둔다** — 표시 형식으로 바꾸는 것은 UI 의
   * 책임이고, 상태에서 미리 가공하면 그 값이 표시용인지 원본인지 다음 소비자가 알 수 없다.
   *
   * 응답 0건이면 `null` 이고 **끝까지 `null` 이다**(상태에서 `''`·`'-'` 로 치환하지 않는다) —
   * 그래야 "응답이 없다" 와 "그런 문자열" 이 구별된다.
   *
   * spec 010 US2 시나리오 1 이 이 두 값의 표시를 인수 조건으로 요구한다.
   */
  firstRespondedAt: string | null;
  lastRespondedAt: string | null;
  /**
   * 전체 응답 수. **빈 결과 판정은 이 값으로 한다** — perQuestion 은 응답이 0건이어도 모든 문항을
   * 싣기 때문에(계약 §7) 길이로는 "아직 아무도 안 답함" 과 "문항이 없음" 이 구별되지 않는다.
   */
  totalResponses: number;
  perQuestion: SurveyQuestionAggregateVM[];
  /** 주관식 첫 페이지 — 다음 페이지는 getTextAnswers 로 (S15P21A604-194) */
  textAnswers: SurveyTextAnswerPage;
}

export interface SurveySubmitResult {
  /** 실제 지급된 코인. 보상 없는 설문·게스트 무보상 제출은 0 이다(키 부재가 아니다) */
  rewardedCoin: number;
}

export interface SurveyPort {
  getRun(boothId: number): Promise<SurveyRunSnapshot>;
  submitAnswers(surveyId: number, answers: Record<string, SurveyAnswerValue>): Promise<SurveySubmitResult>;
  getResult(boothId: number): Promise<SurveyResultSnapshot>;
  getTextAnswers(surveyId: number, page: number): Promise<SurveyTextAnswerPage>;
  /** null = 아직 만든 설문이 없다 (Builder 는 빈 draft 로 시작) */
  getDraft(boothId: number): Promise<SurveyDraftVM | null>;
  saveDraft(boothId: number, draft: SurveyDraftVM): Promise<void>;
}
