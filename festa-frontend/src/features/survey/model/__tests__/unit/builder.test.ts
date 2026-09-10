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
import {
  MOCK_BOOTH_NEW,
  MOCK_BOOTH_NORMAL,
  __resetSurveyMockForTests,
  __savedDraftForTests,
  surveyMockPort,
} from '../../../../../entities/survey/api.mock';

beforeEach(() => {
  __resetSurveyBuilderForTests();
  __resetSurveyMockForTests();
});

async function readyBuilder(): Promise<void> {
  await loadSurveyBuilder(MOCK_BOOTH_NEW);
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
    addQuestion('application');
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
    await loadSurveyBuilder(MOCK_BOOTH_NEW);
    addQuestion('single'); // 빈 prompt + 빈 옵션 2개
    addQuestion('rating');
    const q2 = getSurveyBuilderSnapshot().draft.questions[1].id;
    updateQuestion(q2, { prompt: '만족도?', scale: { min: 5, max: 5 } });
    const issues = validateBuilder();
    expect(issues.some((i) => !i.questionId && i.message.includes('제목'))).toBe(true);
    expect(issues.filter((i) => i.questionId).map((i) => i.message)).toEqual([
      '질문 내용을 입력해주세요.',
      // 선택지 자리는 2개 있고 라벨이 비어 있다 — 개수가 아니라 내용이 문제라고 말한다
      '빈 선택지가 있습니다. 내용을 채우거나 지워주세요.',
      '척도 최솟값은 최댓값보다 작아야 합니다.',
    ]);
  });

  // 예전 규칙은 "비공백 라벨 2개 이상" 이라 뒤에 붙은 빈 선택지를 통과시켰다. 그러면 서버가
  // 400 VALIDATION_FAILED (모든 label 비공백, 계약 §4) 로 거절하는데 화면은 이유를 모른다.
  it('비공백 2개를 채워도 빈 선택지가 남아 있으면 잡는다 — 서버 400 을 미리 막는다', async () => {
    await loadSurveyBuilder(MOCK_BOOTH_NEW);
    updateTitle('부스 만족도 조사');
    addQuestion('single');
    const q = getSurveyBuilderSnapshot().draft.questions[0].id;
    updateQuestion(q, {
      prompt: '어떻게 알았나요?',
      options: [
        { id: 'o-1', label: '월드에서' },
        { id: 'o-2', label: '추천으로' },
        { id: 'o-3', label: '   ' },
      ],
    });
    expect(validateBuilder().map((i) => i.message)).toEqual(['빈 선택지가 있습니다. 내용을 채우거나 지워주세요.']);
  });
});

describe('save', () => {
  it('validation 통과 시에만 저장 — 저장 후 dirty 해제·재로드 시 복원', async () => {
    await readyBuilder();
    addQuestion('application');
    const q = getSurveyBuilderSnapshot().draft.questions[0].id;
    await saveSurveyBuilder(); // prompt 비공백 위반 — no-op
    expect(__savedDraftForTests()).toBeNull();
    updateQuestion(q, { prompt: '추천하시겠습니까?' });
    await saveSurveyBuilder();
    expect(getSurveyBuilderSnapshot()).toMatchObject({ dirty: false, save: { phase: 'success' } });
    expect(__savedDraftForTests()?.draft.title).toBe('부스 만족도 조사');

    __resetSurveyBuilderForTests();
    await loadSurveyBuilder(MOCK_BOOTH_NEW);
    expect(getSurveyBuilderSnapshot().draft.questions).toHaveLength(1);
  });

  it('리로드 후 신규 문항 id 가 저장된 id 와 충돌하지 않는다 (-377)', async () => {
    await readyBuilder();
    addQuestion('single');
    addQuestion('rating');
    const [q1, q2] = getSurveyBuilderSnapshot().draft.questions.map((q) => q.id);
    updateQuestion(q1, { prompt: '질문1', options: [{ id: 'o-1', label: 'A' }, { id: 'o-2', label: 'B' }] });
    updateQuestion(q2, { prompt: '질문2' });
    await saveSurveyBuilder();
    // 새 세션 시뮬레이션 — questionSeq 는 0 부터지만 load 가 저장된 q-N 뒤로 시드한다
    __resetSurveyBuilderForTests();
    await loadSurveyBuilder(MOCK_BOOTH_NEW);
    addQuestion('short_text');
    const ids = getSurveyBuilderSnapshot().draft.questions.map((q) => q.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual([q1, q2, 'q-3']);
  });

  it('저장 실패는 error — draft 유지', async () => {
    await loadSurveyBuilder(MOCK_BOOTH_NEW);
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

// 저장 중 부스를 옮기면 이전 부스의 늦은 응답이 새 부스 상태를 건드렸다 (GitLab #133, 2026-09-08 BE 지적).
// loadSurveyBuilder 에는 가드가 있었는데 저장 경로에만 빠져 있었다.
//
// mock 의 saveDraft 는 즉시 resolve 해서 그냥 두면 순서가 재현되지 않는다 — 응답을 손으로 붙잡아
// **부스를 옮긴 뒤에** 도착하게 만들어야 결함이 드러난다.
describe('저장 늦은 응답 가드', () => {
  it('저장 중 부스를 옮기면 이전 부스의 늦은 응답이 새 부스 상태를 건드리지 않는다', async () => {
    const original = surveyMockPort.saveDraft;
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    surveyMockPort.saveDraft = () => held;

    try {
      await loadSurveyBuilder(MOCK_BOOTH_NEW);
      updateTitle('부스 A 설문');
      addQuestion('short_text');
      updateQuestion(getSurveyBuilderSnapshot().draft.questions[0].id, { prompt: '한마디' });

      const saving = saveSurveyBuilder(); // 부스 A 저장 시작 — 응답은 붙잡혀 있다
      await loadSurveyBuilder(MOCK_BOOTH_NORMAL); // 그 사이 부스를 옮긴다
      expect(getSurveyBuilderSnapshot().save.phase).toBe('idle');

      release(); // 이제서야 부스 A 의 성공 응답이 도착한다
      await saving;

      const s = getSurveyBuilderSnapshot();
      expect(s.boothId).toBe(MOCK_BOOTH_NORMAL);
      // 가드가 없으면 여기서 'success' 가 되고, 새 부스에 dirty 가 있었다면 그것도 함께 꺼진다
      expect(s.save.phase).toBe('idle');
    } finally {
      surveyMockPort.saveDraft = original;
    }
  });
});

// S15P21A604-541 F-1 회귀 — 저장 경로도 같다. 409 SURVEY_LOCKED("응답이 있는 설문은 문항을
// 바꿀 수 없습니다") 가 "저장하지 못했습니다" 로 뭉개지면 사용자가 다음 행동을 정할 수 없다.
describe('저장 실패 문구 (S15P21A604-541 F-1)', () => {
  it('서버 message 를 상태에 싣는다', async () => {
    await loadSurveyBuilder(MOCK_BOOTH_NEW);
    updateTitle('FAIL');
    addQuestion('long_text');
    const q = getSurveyBuilderSnapshot().draft.questions[0].id;
    updateQuestion(q, { prompt: '의견?' });
    await saveSurveyBuilder();

    const s = getSurveyBuilderSnapshot();
    expect(s.save.phase).toBe('error');
    expect(s.save.errorMessage).toBe('일시적인 오류입니다.');
  });
});
