// 이벤트 설문 확정 문항 fixture — 백엔드 시드 V37__event_survey_questions.sql 과 같은 6문항.
// Q6(이벤트 상점 추가 물품)는 여기 그대로 두고 FE 진입부(run.ts)에서 숨긴다 — 실서버도 6개를
// 내리므로 fixture 를 5개로 줄이면 실 경로와 어긋나 필터가 검증되지 않는다.
// mock 모드(VITE_USE_MOCK=true)에서도 실 문항 그대로 보이게 하려고 둔다 — 부스 설문의 6유형
// 데모(RUN_QUESTIONS)와 다른 물건이다. 실 계약 어휘(SurveyQuestionVM)이지 BE DTO 가 아니다.
import type { SurveyQuestionVM } from '../../../shared/contracts/survey';

export const EVENT_RUN_QUESTIONS: SurveyQuestionVM[] = [
  {
    id: 'ev-q1',
    type: 'rating',
    prompt: '전시 부스에서 다른 팀의 프로젝트를 둘러보는 과정이 얼마나 편리했나요? (1 = 매우 불편했다 · 5 = 매우 편리했다)',
    required: true,
    scale: { min: 1, max: 5 },
  },
  {
    id: 'ev-q2',
    type: 'rating',
    prompt: 'SSAFESTA를 다시 이용할 의향이 있나요? (1 = 전혀 없다 · 5 = 매우 있다)',
    required: true,
    scale: { min: 1, max: 5 },
  },
  {
    id: 'ev-q3',
    type: 'multi',
    prompt: 'SSAFESTA를 다시 이용한다면 어떤 기능을 가장 기대하시나요? 모두 선택해주세요.',
    required: true,
    options: [
      { id: 'ev-q3-o0', label: '다른 팀의 프로젝트·부스 둘러보기' },
      { id: 'ev-q3-o1', label: '미니게임' },
      { id: 'ev-q3-o2', label: 'AI NPC·상담' },
      { id: 'ev-q3-o3', label: '이벤트·경품 콘텐츠' },
      { id: 'ev-q3-o4', label: '캐릭터·월드 탐색' },
      { id: 'ev-q3-o5', label: '부스 꾸미기·프로젝트 전시' },
      { id: 'ev-q3-o6', label: '다른 참가자와의 소통' },
    ],
  },
  {
    id: 'ev-q4',
    type: 'multi',
    prompt: '이용하면서 아쉽거나 부족하다고 느낀 점을 모두 선택해주세요.',
    required: true,
    options: [
      { id: 'ev-q4-o0', label: '월드 이동·조작이 불편했다' },
      { id: 'ev-q4-o1', label: '원하는 부스나 프로젝트를 찾기 어려웠다' },
      { id: 'ev-q4-o2', label: '즐길 콘텐츠가 부족했다' },
      { id: 'ev-q4-o3', label: '미니게임이 부족했다' },
      { id: 'ev-q4-o4', label: '다른 참가자와 상호작용할 요소가 부족했다' },
      { id: 'ev-q4-o5', label: '채팅이나 음성채팅 기능이 필요하다고 생각한다' },
      { id: 'ev-q4-o6', label: '로딩·성능이 불편했다' },
      { id: 'ev-q4-o7', label: 'UI가 이해하기 어려웠다' },
      { id: 'ev-q4-o8', label: '특별히 아쉬운 점이 없었다' },
    ],
  },
  { id: 'ev-q5', type: 'long_text', prompt: '추가로 개선했으면 하는 점이 있다면 자유롭게 적어주세요.', required: false },
  { id: 'ev-q6', type: 'short_text', prompt: '이벤트 상점에 추가되었으면 하는 경품이나 물품이 있다면 적어주세요.', required: false },
];
