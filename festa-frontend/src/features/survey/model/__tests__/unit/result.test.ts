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
import {
  MOCK_BOOTH_EMPTY,
  MOCK_BOOTH_NORMAL,
  MOCK_BOOTH_RESULT_FAIL,
  __resetSurveyMockForTests,
} from '../../../../../entities/survey/api.mock';

beforeEach(() => {
  __resetSurveyResultForTests();
  __resetSurveyMockForTests();
});

describe('loadSurveyResult', () => {
  it('집계 3건(rating 은 평균+분포, FR-006) + 주관식 첫 페이지 5건 로드', async () => {
    await loadSurveyResult(MOCK_BOOTH_NORMAL);
    const s = getSurveyResultSnapshot();
    expect(s.status).toBe('ready');
    expect(s.perQuestion.map((a) => a.kind)).toEqual(['choice', 'choice', 'rating']);
    const rating = s.perQuestion[2];
    expect(rating.kind === 'rating' && rating.distribution).toHaveLength(5);
    expect(s.textAnswers.items).toHaveLength(5);
    expect(s.textAnswers.hasNext).toBe(true);
  });

  it('응답 0 은 empty, 실패는 error', async () => {
    await loadSurveyResult(MOCK_BOOTH_EMPTY);
    expect(getSurveyResultSnapshot().status).toBe('empty');
    await loadSurveyResult(MOCK_BOOTH_RESULT_FAIL);
    expect(getSurveyResultSnapshot().status).toBe('error');
  });

  // 계약 §7 은 응답이 0건이어도 perQuestion 에 **모든 문항**을 싣는다. 그래서 빈 판정을
  // perQuestion.length 로 하면 영영 걸리지 않는다 — totalResponses 가 기준이어야 한다.
  it('문항은 있는데 응답이 0건이면 empty — 길이가 아니라 응답 수로 판정한다', async () => {
    await loadSurveyResult(MOCK_BOOTH_EMPTY);
    const s = getSurveyResultSnapshot();
    expect(s.totalResponses).toBe(0);
    expect(s.perQuestion.length).toBeGreaterThan(0);
    expect(s.status).toBe('empty');
  });

  it('응답이 있으면 전체 응답 수가 상태에 실린다 — 화면이 분모와 함께 보여준다', async () => {
    await loadSurveyResult(MOCK_BOOTH_NORMAL);
    expect(getSurveyResultSnapshot().totalResponses).toBeGreaterThan(0);
  });
});

describe('loadNextTextPage (-194)', () => {
  it('페이지를 누적한다 — 5·10·12건, 마지막에 hasNext=false', async () => {
    await loadSurveyResult(MOCK_BOOTH_NORMAL);
    await loadNextTextPage();
    expect(getSurveyResultSnapshot().textAnswers.items).toHaveLength(10);
    await loadNextTextPage();
    const s = getSurveyResultSnapshot();
    expect(s.textAnswers.items).toHaveLength(12);
    expect(s.textAnswers.hasNext).toBe(false);
  });

  it('hasNext=false 이후 추가 호출은 no-op', async () => {
    await loadSurveyResult(MOCK_BOOTH_NORMAL);
    await loadNextTextPage();
    await loadNextTextPage();
    await loadNextTextPage(); // 경계 밖
    expect(getSurveyResultSnapshot().textAnswers.items).toHaveLength(12);
  });

  it('ready 전에는 no-op', async () => {
    await loadNextTextPage();
    expect(getSurveyResultSnapshot().textAnswers.items).toHaveLength(0);
  });

  it('페이지 요청 중 설문을 전환하면 이전 설문 페이지가 새 상태를 오염시키지 않는다 (-377)', async () => {
    await loadSurveyResult(MOCK_BOOTH_NORMAL);
    const stale = loadNextTextPage(); // s1 의 page 1 요청 in-flight
    await loadSurveyResult(MOCK_BOOTH_EMPTY); // 다른 설문으로 전환
    await stale;
    const s = getSurveyResultSnapshot();
    expect(s.boothId).toBe(MOCK_BOOTH_EMPTY);
    expect(s.status).toBe('empty');
    expect(s.textAnswers.items).toHaveLength(0);
  });
});
