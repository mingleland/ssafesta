// Survey Port — FE 가 필요로 하는 데이터 요구의 표현이다 (S15P21A604-368).
// BE Survey API(-130~-132·-190·-193)는 미착수 — 이 시그니처는 endpoint·DTO 가 아니며,
// BE 계약 합의 시 real 어댑터 + Mapper 가 이 Port 를 구현한다. FE 가 외부 계약을 발명하지 않는다.
import type { SurveyAnswerValue, SurveyQuestionAggregateVM, SurveyQuestionVM } from '../../shared/contracts/survey';

export interface SurveyRunSnapshot {
  /** closed = 마감 — 질문이 있어도 제출할 수 없다 */
  status: 'open' | 'closed';
  questions: SurveyQuestionVM[];
}

export interface SurveyTextAnswerPage {
  items: string[];
  page: number;
  hasNext: boolean;
}

export interface SurveyResultSnapshot {
  perQuestion: SurveyQuestionAggregateVM[];
  /** 주관식 첫 페이지 — 다음 페이지는 getTextAnswers 로 (S15P21A604-194) */
  textAnswers: SurveyTextAnswerPage;
}

export interface SurveyPort {
  getRun(surveyId: string): Promise<SurveyRunSnapshot>;
  submitAnswers(surveyId: string, answers: Record<string, SurveyAnswerValue>): Promise<void>;
  getResult(surveyId: string): Promise<SurveyResultSnapshot>;
  getTextAnswers(surveyId: string, page: number): Promise<SurveyTextAnswerPage>;
}
