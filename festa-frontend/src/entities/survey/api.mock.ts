// Survey mock 어댑터 — SurveyPort 의 유일한 구현 (real 은 BE -130 착수 후).
// surveyId 시나리오: 'empty' = 문항 0, 'closed' = 마감, 'submit-fail' = 제출 오류, 그 외 = normal.
import type { ApiError } from '../../shared/api/client';
import type { SurveyAnswerValue } from '../../shared/contracts/survey';
import type { SurveyPort, SurveyResultSnapshot, SurveyRunSnapshot, SurveyTextAnswerPage } from './api.port';
import { RUN_QUESTIONS } from './fixtures/run';
import { RESULT_AGGREGATES, TEXT_ANSWERS, TEXT_PAGE_SIZE } from './fixtures/result';

function apiError(code: string, message: string): ApiError {
  return { code, message, errors: [], warnings: [] };
}

let submitted: Record<string, SurveyAnswerValue> | null = null;

export const surveyMockPort: SurveyPort = {
  async getRun(surveyId: string): Promise<SurveyRunSnapshot> {
    if (surveyId === 'empty') return { status: 'open', questions: [] };
    if (surveyId === 'closed') return { status: 'closed', questions: RUN_QUESTIONS };
    return { status: 'open', questions: RUN_QUESTIONS };
  },

  async submitAnswers(surveyId: string, answers: Record<string, SurveyAnswerValue>): Promise<void> {
    if (surveyId === 'submit-fail') throw apiError('UNKNOWN', '일시적인 오류입니다.');
    submitted = { ...answers };
  },

  async getResult(surveyId: string): Promise<SurveyResultSnapshot> {
    if (surveyId === 'result-fail') throw apiError('UNKNOWN', '일시적인 오류입니다.');
    if (surveyId === 'empty') return { perQuestion: [], textAnswers: { items: [], page: 0, hasNext: false } };
    return { perQuestion: RESULT_AGGREGATES, textAnswers: textPage(0) };
  },

  async getTextAnswers(_surveyId: string, page: number): Promise<SurveyTextAnswerPage> {
    return textPage(page);
  },
};

function textPage(page: number): SurveyTextAnswerPage {
  const start = page * TEXT_PAGE_SIZE;
  return {
    items: TEXT_ANSWERS.slice(start, start + TEXT_PAGE_SIZE),
    page,
    hasNext: start + TEXT_PAGE_SIZE < TEXT_ANSWERS.length,
  };
}

export function __submittedAnswersForTests(): Record<string, SurveyAnswerValue> | null {
  return submitted;
}

export function __resetSurveyMockForTests(): void {
  submitted = null;
}
