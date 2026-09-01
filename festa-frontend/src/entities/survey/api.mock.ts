// Survey mock 어댑터 — SurveyPort 의 유일한 구현 (real 은 BE -130 착수 후).
// surveyId 시나리오: 'empty' = 문항 0, 'closed' = 마감, 'submit-fail' = 제출 오류, 그 외 = normal.
import type { ApiError } from '../../shared/api/client';
import type { SurveyAnswerValue } from '../../shared/contracts/survey';
import type { SurveyPort, SurveyRunSnapshot } from './api.port';
import { RUN_QUESTIONS } from './fixtures/run';

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
};

export function __submittedAnswersForTests(): Record<string, SurveyAnswerValue> | null {
  return submitted;
}

export function __resetSurveyMockForTests(): void {
  submitted = null;
}
