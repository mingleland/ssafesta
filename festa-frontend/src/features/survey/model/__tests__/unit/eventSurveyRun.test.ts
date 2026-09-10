// 이벤트 설문 진입 — 부스 설문과 **같은 상태 기계**를 쓴다 (S15P21A604-608).
//
// 이 파일이 지키는 것: ① 진입 함수가 하나이고 source 로만 갈린다 ② 두 source 가 같은 스토어를
// 쓰므로 교차 진입의 늦은 응답이 서로를 덮지 않는다 ③ 이미 참여한 설문은 제출 버튼이 살아나지
// 않는다. ①이 깨지면 문항 유형이 늘 때 고칠 곳이 두 곳이 된다.
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
  setAnswer,
} from '../../run';
import {
  MOCK_BOOTH_NORMAL,
  MOCK_EVENT_SURVEY_CLOSED,
  MOCK_EVENT_SURVEY_DONE,
  MOCK_EVENT_SURVEY_KEY,
  __resetSurveyMockForTests,
} from '../../../../../entities/survey/api.mock';

beforeEach(() => {
  __resetSurveyRunForTests();
  __resetSurveyMockForTests();
});

describe('이벤트 설문 로드', () => {
  it('surveyKey 로 들어오고 부스 설문과 같은 문항 상태를 만든다', async () => {
    await loadSurveyRun({ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY });

    const state = getSurveyRunSnapshot();
    expect(state.status).toBe('ready');
    expect(state.source).toEqual({ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY });
    expect(state.surveyId).not.toBeNull();
    expect(state.progress.total).toBe(state.questions.length);
  });

  it('보상이 0 인데도 회원 전용이다 — rewardCoin 으로 게스트를 판정하면 이 설문이 열려 버린다', async () => {
    await loadSurveyRun({ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY });

    expect(getSurveyRunSnapshot().rewardCoin).toBe(0);
    expect(getSurveyRunSnapshot().memberOnly).toBe(true);
  });

  it('없는 key 는 오류다 — 가짜 대상으로 되돌아가지 않는다', async () => {
    await loadSurveyRun({ kind: 'event', surveyKey: 'NOPE' });

    expect(getSurveyRunSnapshot().status).toBe('error');
  });

  it('마감된 이벤트 설문도 문항을 남긴다 (계약 §5 와 같은 규칙)', async () => {
    await loadSurveyRun({ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_CLOSED });

    expect(getSurveyRunSnapshot().status).toBe('closed');
    expect(getSurveyRunSnapshot().questions.length).toBeGreaterThan(0);
  });
});

describe('이미 참여한 설문', () => {
  it('참여 시각을 싣고 제출을 막는다 — 눌러서 409 를 받는 버튼을 두지 않는다', async () => {
    await loadSurveyRun({ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_DONE });
    setAnswer('q-single', { type: 'single', optionId: 'o1' });
    setAnswer('q-multi', { type: 'multi', optionIds: ['o1'] });
    setAnswer('q-rating', { type: 'rating', value: 5 });

    expect(getSurveyRunSnapshot().respondedAt).not.toBeNull();
    expect(canSubmit()).toBe(false);
  });
});

describe('늦은 응답 가드 — 두 source 가 한 스토어를 쓴다', () => {
  it('부스로 갈아탄 뒤 도착한 이벤트 응답이 화면을 덮지 않는다', async () => {
    const stale = loadSurveyRun({ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY });
    const current = loadSurveyRun({ kind: 'booth', boothId: MOCK_BOOTH_NORMAL });
    await Promise.all([stale, current]);

    expect(getSurveyRunSnapshot().source).toEqual({ kind: 'booth', boothId: MOCK_BOOTH_NORMAL });
  });

  it('이벤트로 갈아탄 뒤 도착한 부스 응답이 화면을 덮지 않는다', async () => {
    const stale = loadSurveyRun({ kind: 'booth', boothId: MOCK_BOOTH_NORMAL });
    const current = loadSurveyRun({ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY });
    await Promise.all([stale, current]);

    expect(getSurveyRunSnapshot().source).toEqual({ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY });
    expect(getSurveyRunSnapshot().memberOnly).toBe(true);
  });
});
