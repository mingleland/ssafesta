import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/survey/api.select', async () => {
  const { surveyMockPort } = await import('../../../../../entities/survey/api.mock');
  return { surveyApi: surveyMockPort };
});

import {
  __resetSurveyBuilderForTests,
  addQuestion,
  getSurveyBuilderSnapshot,
  loadSurveyBuilder,
  removeQuestion,
  reorderQuestion,
  saveSurveyBuilder,
  updateQuestion,
  updateTitle,
  validateBuilder,
} from '../../builder';
import { __resetSurveyMockForTests, __savedDraftForTests } from '../../../../../entities/survey/api.mock';

beforeEach(() => {
  __resetSurveyBuilderForTests();
  __resetSurveyMockForTests();
});

async function readyBuilder(): Promise<void> {
  await loadSurveyBuilder();
  updateTitle('부스 만족도 조사');
}

describe('draft 편집', () => {
  it('처음엔 빈 draft — 유형별 골격으로 문항 추가', async () => {
    await readyBuilder();
    addQuestion('single');
    addQuestion('rating');
    const s = getSurveyBuilderSnapshot();
    expect(s.draft.questions).toHaveLength(2);
    expect(s.draft.questions[0].options).toHaveLength(2);
    expect(s.draft.questions[1].scale).toEqual({ min: 1, max: 5 });
    expect(s.dirty).toBe(true);
  });

  it('remove·reorder — 경계 밖 reorder 는 no-op', async () => {
    await readyBuilder();
    addQuestion('single');
    addQuestion('boolean');
    addQuestion('short_text');
    const ids = getSurveyBuilderSnapshot().draft.questions.map((q) => q.id);
    reorderQuestion(2, 0);
    expect(getSurveyBuilderSnapshot().draft.questions.map((q) => q.id)).toEqual([ids[2], ids[0], ids[1]]);
    reorderQuestion(0, 99);
    expect(getSurveyBuilderSnapshot().draft.questions).toHaveLength(3);
    removeQuestion(ids[0]);
    expect(getSurveyBuilderSnapshot().draft.questions.map((q) => q.id)).toEqual([ids[2], ids[1]]);
  });
});

describe('validation', () => {
  it('빈 제목·빈 문항·옵션 부족·척도 역전을 잡는다', async () => {
    await loadSurveyBuilder();
    addQuestion('single'); // 빈 prompt + 빈 옵션 2개
    addQuestion('rating');
    const q2 = getSurveyBuilderSnapshot().draft.questions[1].id;
    updateQuestion(q2, { prompt: '만족도?', scale: { min: 5, max: 5 } });
    const issues = validateBuilder();
    expect(issues.some((i) => !i.questionId && i.message.includes('제목'))).toBe(true);
    expect(issues.filter((i) => i.questionId).map((i) => i.message)).toEqual([
      '질문 내용을 입력해주세요.',
      '선택지는 2개 이상 필요합니다.',
      '척도 최솟값은 최댓값보다 작아야 합니다.',
    ]);
  });
});

describe('save', () => {
  it('validation 통과 시에만 저장 — 저장 후 dirty 해제·재로드 시 복원', async () => {
    await readyBuilder();
    addQuestion('boolean');
    const q = getSurveyBuilderSnapshot().draft.questions[0].id;
    await saveSurveyBuilder(); // prompt 비공백 위반 — no-op
    expect(__savedDraftForTests()).toBeNull();
    updateQuestion(q, { prompt: '추천하시겠습니까?' });
    await saveSurveyBuilder();
    expect(getSurveyBuilderSnapshot()).toMatchObject({ dirty: false, save: { phase: 'success' } });
    expect(__savedDraftForTests()?.title).toBe('부스 만족도 조사');

    __resetSurveyBuilderForTests();
    await loadSurveyBuilder();
    expect(getSurveyBuilderSnapshot().draft.questions).toHaveLength(1);
  });

  it('저장 실패는 error — draft 유지', async () => {
    await loadSurveyBuilder();
    updateTitle('FAIL');
    addQuestion('long_text');
    const q = getSurveyBuilderSnapshot().draft.questions[0].id;
    updateQuestion(q, { prompt: '의견?' });
    await saveSurveyBuilder();
    const s = getSurveyBuilderSnapshot();
    expect(s.save.phase).toBe('error');
    expect(s.draft.title).toBe('FAIL');
    expect(s.dirty).toBe(true);
  });
});
