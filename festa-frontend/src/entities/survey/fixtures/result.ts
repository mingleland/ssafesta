// Survey Result fixture — run.ts 의 6문항과 짝을 이루는 집계 (FE UX Contract 어휘).
import type { SurveyQuestionAggregateVM } from '../../../shared/contracts/survey';

export const RESULT_AGGREGATES: SurveyQuestionAggregateVM[] = [
  {
    questionId: 'q-single',
    kind: 'choice',
    counts: [
      { optionId: 'o1', label: '프로젝트 전시', count: 12 },
      { optionId: 'o2', label: '미니게임', count: 9 },
      { optionId: 'o3', label: 'AI 안내', count: 4 },
    ],
  },
  {
    questionId: 'q-multi',
    kind: 'choice',
    counts: [
      { optionId: 'o1', label: '콘텐츠', count: 15 },
      { optionId: 'o2', label: '분위기', count: 11 },
      { optionId: 'o3', label: '보상', count: 8 },
    ],
  },
  { questionId: 'q-rating', kind: 'rating', average: 4.2, count: 25 },
  { questionId: 'q-boolean', kind: 'boolean', yes: 21, no: 4 },
];

/** 주관식 응답 12건 — 페이지 크기 5 로 3페이지 (-194 페이지네이션 경계 재현) */
export const TEXT_ANSWERS: string[] = Array.from({ length: 12 }, (_, i) => `주관식 응답 ${i + 1}`);

export const TEXT_PAGE_SIZE = 5;
