import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/survey/api.select', async () => {
  const { surveyMockPort } = await import('../../../../../entities/survey/api.mock');
  return { surveyApi: surveyMockPort };
});

import {
  __resetSurveyRunForTests,
  canSubmit,
  getSurveyRunSnapshot,
  loadSurveyRun,
  missingRequired,
  setAnswer,
  submitSurveyRun,
} from '../../run';
import { __resetSurveyMockForTests, __submittedAnswersForTests } from '../../../../../entities/survey/api.mock';

beforeEach(() => {
  __resetSurveyRunForTests();
  __resetSurveyMockForTests();
});

async function loadAndFillRequired(): Promise<void> {
  await loadSurveyRun('s1');
  setAnswer('q-single', { type: 'single', optionId: 'o1' });
  setAnswer('q-rating', { type: 'rating', value: 5 });
  setAnswer('q-boolean', { type: 'boolean', value: true });
}

describe('loadSurveyRun', () => {
  it('normal — 6유형 전부 로드, progress 0/6', async () => {
    await loadSurveyRun('s1');
    const s = getSurveyRunSnapshot();
    expect(s.status).toBe('ready');
    expect(s.questions.map((q) => q.type)).toEqual(['single', 'multi', 'rating', 'boolean', 'short_text', 'long_text']);
    expect(s.progress).toEqual({ current: 0, total: 6 });
  });

  it('문항 0 은 empty, 마감은 closed', async () => {
    await loadSurveyRun('empty');
    expect(getSurveyRunSnapshot().status).toBe('empty');
    await loadSurveyRun('closed');
    expect(getSurveyRunSnapshot().status).toBe('closed');
  });
});

describe('answers·required', () => {
  it('답할 때마다 progress 가 오르고, required 3건이 비면 제출 불가', async () => {
    await loadSurveyRun('s1');
    expect(missingRequired()).toEqual(['q-single', 'q-rating', 'q-boolean']);
    expect(canSubmit()).toBe(false);
    setAnswer('q-single', { type: 'single', optionId: 'o1' });
    expect(getSurveyRunSnapshot().progress.current).toBe(1);
  });

  it('required 전부 답하면 제출 가능 — optional 미응답은 막지 않는다', async () => {
    await loadAndFillRequired();
    expect(missingRequired()).toEqual([]);
    expect(canSubmit()).toBe(true);
  });

  it('closed 상태에서는 setAnswer 가 무시된다', async () => {
    await loadSurveyRun('closed');
    setAnswer('q-single', { type: 'single', optionId: 'o1' });
    expect(getSurveyRunSnapshot().answers).toEqual({});
  });
});

describe('submitSurveyRun', () => {
  it('성공 시 success — 제출된 답이 Port 로 전달된다', async () => {
    await loadAndFillRequired();
    setAnswer('q-short', { type: 'short_text', text: '재밌었어요' });
    await submitSurveyRun();
    expect(getSurveyRunSnapshot().submit.phase).toBe('success');
    expect(Object.keys(__submittedAnswersForTests() ?? {})).toHaveLength(4);
  });

  it('required 미충족이면 no-op', async () => {
    await loadSurveyRun('s1');
    await submitSurveyRun();
    expect(getSurveyRunSnapshot().submit.phase).toBe('idle');
    expect(__submittedAnswersForTests()).toBeNull();
  });

  it('제출 실패는 error — 답은 유지되어 재시도 가능', async () => {
    await loadSurveyRun('submit-fail');
    setAnswer('q-single', { type: 'single', optionId: 'o1' });
    setAnswer('q-rating', { type: 'rating', value: 3 });
    setAnswer('q-boolean', { type: 'boolean', value: false });
    await submitSurveyRun();
    const s = getSurveyRunSnapshot();
    expect(s.submit.phase).toBe('error');
    expect(Object.keys(s.answers)).toHaveLength(3);
  });
});
