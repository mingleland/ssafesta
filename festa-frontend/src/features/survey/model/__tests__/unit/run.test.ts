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
import {
  MOCK_BOOTH_CLOSED,
  MOCK_BOOTH_EMPTY,
  MOCK_BOOTH_NORMAL,
  MOCK_BOOTH_REWARDED,
  MOCK_BOOTH_SUBMIT_FAIL,
  __resetSurveyMockForTests,
  __submittedAnswersForTests,
} from '../../../../../entities/survey/api.mock';

beforeEach(() => {
  __resetSurveyRunForTests();
  __resetSurveyMockForTests();
});

async function loadAndFillRequired(): Promise<void> {
  await loadSurveyRun(MOCK_BOOTH_NORMAL);
  setAnswer('q-single', { type: 'single', optionId: 'o1' });
  setAnswer('q-multi', { type: 'multi', optionIds: ['o1', 'o2'] });
  setAnswer('q-rating', { type: 'rating', value: 5 });
}

describe('loadSurveyRun', () => {
  it('normal — spec 010 FR-002 의 6유형 전부 로드, progress 0/6', async () => {
    await loadSurveyRun(MOCK_BOOTH_NORMAL);
    const s = getSurveyRunSnapshot();
    expect(s.status).toBe('ready');
    expect(s.questions.map((q) => q.type)).toEqual([
      'single',
      'multi',
      'rating',
      'short_text',
      'long_text',
      'application',
    ]);
    expect(s.progress).toEqual({ current: 0, total: 6 });
  });

  it('문항 0 은 empty, 마감은 closed', async () => {
    await loadSurveyRun(MOCK_BOOTH_EMPTY);
    expect(getSurveyRunSnapshot().status).toBe('empty');
    await loadSurveyRun(MOCK_BOOTH_CLOSED);
    expect(getSurveyRunSnapshot().status).toBe('closed');
  });
});

describe('answers·required', () => {
  it('답할 때마다 progress 가 오르고, required 3건이 비면 제출 불가', async () => {
    await loadSurveyRun(MOCK_BOOTH_NORMAL);
    expect(missingRequired()).toEqual(['q-single', 'q-multi', 'q-rating']);
    expect(canSubmit()).toBe(false);
    setAnswer('q-single', { type: 'single', optionId: 'o1' });
    expect(getSurveyRunSnapshot().progress.current).toBe(1);
  });

  it('빈 값은 required 를 채우지 못한다 — 빈 multi 선택 (-377)', async () => {
    await loadAndFillRequired();
    expect(canSubmit()).toBe(true);
    setAnswer('q-multi', { type: 'multi', optionIds: [] });
    expect(missingRequired()).toEqual(['q-multi']);
    expect(canSubmit()).toBe(false);
  });

  it('required 전부 답하면 제출 가능 — optional 미응답은 막지 않는다', async () => {
    await loadAndFillRequired();
    expect(missingRequired()).toEqual([]);
    expect(canSubmit()).toBe(true);
  });

  it('closed 상태에서는 setAnswer 가 무시된다', async () => {
    await loadSurveyRun(MOCK_BOOTH_CLOSED);
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
    await loadSurveyRun(MOCK_BOOTH_NORMAL);
    await submitSurveyRun();
    expect(getSurveyRunSnapshot().submit.phase).toBe('idle');
    expect(__submittedAnswersForTests()).toBeNull();
  });

  it('보상이 걸린 설문은 지급 코인이 상태에 실린다 — 완료 화면이 그 값을 보여준다', async () => {
    await loadSurveyRun(MOCK_BOOTH_REWARDED);
    setAnswer('q-single', { type: 'single', optionId: 'o1' });
    setAnswer('q-multi', { type: 'multi', optionIds: ['o1'] });
    setAnswer('q-rating', { type: 'rating', value: 4 });
    expect(getSurveyRunSnapshot().rewardCoin).toBeGreaterThan(0);
    await submitSurveyRun();
    const s = getSurveyRunSnapshot();
    expect(s.submit.phase).toBe('success');
    expect(s.submit.rewardedCoin).toBe(s.rewardCoin);
  });

  it('보상 없는 설문의 지급 코인은 0 이다 — 키 부재가 아니다', async () => {
    await loadAndFillRequired();
    await submitSurveyRun();
    expect(getSurveyRunSnapshot().submit.rewardedCoin).toBe(0);
  });

  it('제출 실패는 error — 답은 유지되어 재시도 가능', async () => {
    await loadSurveyRun(MOCK_BOOTH_SUBMIT_FAIL);
    setAnswer('q-single', { type: 'single', optionId: 'o1' });
    setAnswer('q-multi', { type: 'multi', optionIds: ['o1'] });
    setAnswer('q-rating', { type: 'rating', value: 3 });
    await submitSurveyRun();
    const s = getSurveyRunSnapshot();
    expect(s.submit.phase).toBe('error');
    expect(Object.keys(s.answers)).toHaveLength(3);
  });
});

// S15P21A604-541 F-1 회귀 — 서버가 준 사용자용 문장을 버리지 않는다.
// 회귀 당시 catch {} 가 오류를 통째로 버려 409 SURVEY_ALREADY_RESPONDED("이미 응답한
// 설문입니다") 가 "다시 시도해 주세요" 로 뭉개졌다 — 재시도로 풀리지 않는 오류인데도.
// docs/08 §1.3-1 이 봉투 최상위 message 를 사용자용 문장으로 규정한다.
describe('제출 실패 문구 (S15P21A604-541 F-1)', () => {
  it('서버 message 를 상태에 싣는다', async () => {
    await loadSurveyRun(MOCK_BOOTH_SUBMIT_FAIL);
    setAnswer('q-single', { type: 'single', optionId: 'o1' });
    setAnswer('q-multi', { type: 'multi', optionIds: ['o1'] });
    setAnswer('q-rating', { type: 'rating', value: 3 });
    await submitSurveyRun();

    const s = getSurveyRunSnapshot();
    expect(s.submit.phase).toBe('error');
    expect(s.submit.errorMessage).toBe('일시적인 오류입니다.');
  });
});
