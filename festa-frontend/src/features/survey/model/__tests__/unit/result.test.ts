import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/survey/api.select', async () => {
  const { surveyMockPort } = await import('../../../../../entities/survey/api.mock');
  return { surveyApi: surveyMockPort };
});

import {
  __resetSurveyResultForTests,
  getSurveyResultSnapshot,
  loadNextTextPage,
  loadSurveyResult,
} from '../../result';

beforeEach(() => {
  __resetSurveyResultForTests();
});

describe('loadSurveyResult', () => {
  it('집계 4건 + 주관식 첫 페이지 5건 로드', async () => {
    await loadSurveyResult('s1');
    const s = getSurveyResultSnapshot();
    expect(s.status).toBe('ready');
    expect(s.perQuestion.map((a) => a.kind)).toEqual(['choice', 'choice', 'rating', 'boolean']);
    expect(s.textAnswers.items).toHaveLength(5);
    expect(s.textAnswers.hasNext).toBe(true);
  });

  it('응답 0 은 empty, 실패는 error', async () => {
    await loadSurveyResult('empty');
    expect(getSurveyResultSnapshot().status).toBe('empty');
    await loadSurveyResult('result-fail');
    expect(getSurveyResultSnapshot().status).toBe('error');
  });
});

describe('loadNextTextPage (-194)', () => {
  it('페이지를 누적한다 — 5·10·12건, 마지막에 hasNext=false', async () => {
    await loadSurveyResult('s1');
    await loadNextTextPage();
    expect(getSurveyResultSnapshot().textAnswers.items).toHaveLength(10);
    await loadNextTextPage();
    const s = getSurveyResultSnapshot();
    expect(s.textAnswers.items).toHaveLength(12);
    expect(s.textAnswers.hasNext).toBe(false);
  });

  it('hasNext=false 이후 추가 호출은 no-op', async () => {
    await loadSurveyResult('s1');
    await loadNextTextPage();
    await loadNextTextPage();
    await loadNextTextPage(); // 경계 밖
    expect(getSurveyResultSnapshot().textAnswers.items).toHaveLength(12);
  });

  it('ready 전에는 no-op', async () => {
    await loadNextTextPage();
    expect(getSurveyResultSnapshot().textAnswers.items).toHaveLength(0);
  });
});
