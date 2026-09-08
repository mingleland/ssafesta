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
  items: string[];
  page: number;
  hasNext: boolean;
}

export interface SurveyResultSnapshot {
  surveyId: number;
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
