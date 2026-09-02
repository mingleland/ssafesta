// Survey Run fixture — 6유형 각 1문항 (spec 010 FR-002: 객관식·복수선택·별점·단답·장문·지원서).
// BE DTO 아님 — FE UX Contract 어휘.
import type { SurveyQuestionVM } from '../../../shared/contracts/survey';

export const RUN_QUESTIONS: SurveyQuestionVM[] = [
  {
    id: 'q-single',
    type: 'single',
    prompt: '부스에서 가장 인상 깊었던 것은?',
    required: true,
    options: [
      { id: 'o1', label: '프로젝트 전시' },
      { id: 'o2', label: '미니게임' },
      { id: 'o3', label: 'AI 안내' },
    ],
  },
  {
    id: 'q-multi',
    type: 'multi',
    prompt: '다시 방문하고 싶은 이유를 모두 고르세요',
    required: true,
    options: [
      { id: 'o1', label: '콘텐츠' },
      { id: 'o2', label: '분위기' },
      { id: 'o3', label: '보상' },
    ],
  },
  { id: 'q-rating', type: 'rating', prompt: '전반적인 만족도를 평가해주세요', required: true, scale: { min: 1, max: 5 } },
  { id: 'q-short', type: 'short_text', prompt: '한 줄 소감을 남겨주세요', required: false },
  { id: 'q-long', type: 'long_text', prompt: '개선할 점을 자유롭게 적어주세요', required: false },
  // 지원서(C-03 특별 처리 미확정 — 장문 입력 계열로 최소 처리)
  { id: 'q-application', type: 'application', prompt: '채용 연계에 관심 있다면 지원 동기를 남겨주세요', required: false },
];
