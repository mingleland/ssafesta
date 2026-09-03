// Survey 계약 정본 — Shared Contract Freeze Candidate v0.1 (2026-09-01, -377 정정).
// FE UX Contract 만 확정한다 — BE endpoint·DTO·DB·Unity 이벤트는 미확정(UNKNOWN)이며 여기 없다.
// 6유형은 spec 010 FR-002 전사: 객관식(single)·복수선택(multi)·별점(rating)·단답(short_text)·
// 장문(long_text)·지원서(application). BE(-130·-190) 착수 시 Adapter/Mapper 만 추가, 이 타입 불변이 목표.

export type SurveyQuestionType = 'single' | 'multi' | 'rating' | 'short_text' | 'long_text' | 'application';

export interface SurveyQuestionVM {
  id: string;
  type: SurveyQuestionType;
  prompt: string;
  required: boolean;
  /** single·multi 전용 */
  options?: { id: string; label: string }[];
  /** rating 전용 */
  scale?: { min: number; max: number };
}

export type SurveyAnswerValue =
  | { type: 'single'; optionId: string }
  | { type: 'multi'; optionIds: string[] }
  | { type: 'rating'; value: number }
  | { type: 'short_text'; text: string }
  | { type: 'long_text'; text: string }
  // 지원서 — 특별 처리(제출자별 상세 조회)는 C-03 미확정: 확정 전에는 장문 입력 계열로만 다룬다
  | { type: 'application'; text: string };

export type SurveyRunStatus = 'idle' | 'loading' | 'ready' | 'empty' | 'error' | 'closed';

export interface SurveyRunVM {
  status: SurveyRunStatus;
  questions: SurveyQuestionVM[];
  answers: Record<string, SurveyAnswerValue>;
  progress: { current: number; total: number };
  submit: { phase: 'idle' | 'submitting' | 'success' | 'error' };
}

export interface SurveyResultVM {
  status: 'idle' | 'loading' | 'ready' | 'empty' | 'error';
  perQuestion: SurveyQuestionAggregateVM[];
  textAnswers: { items: string[]; page: number; hasNext: boolean };
}

export type SurveyQuestionAggregateVM =
  | { questionId: string; kind: 'choice'; counts: { optionId: string; label: string; count: number }[] }
  // FR-006 — 별점은 평균·분포를 함께 제공한다
  | {
      questionId: string;
      kind: 'rating';
      average: number;
      count: number;
      distribution: { value: number; count: number }[];
    };

export interface SurveyBuilderIssueVM {
  questionId?: string;
  message: string;
}

export interface SurveyDraftVM {
  title: string;
  questions: SurveyQuestionVM[];
}
