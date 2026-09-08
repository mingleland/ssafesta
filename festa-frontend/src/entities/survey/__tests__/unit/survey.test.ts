// Survey wire ↔ VM 매퍼와 실 어댑터 (S15P21A604-528).
// 계약: specs/010-survey/contracts/survey-api.md — 인용한 절 번호는 그 문서 기준이다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '../../../../shared/api/client';
import type { SurveyAnswerValue, SurveyDraftVM, SurveyQuestionType } from '../../../../shared/contracts/survey';
import { surveyHttpPort } from '../../api';
import {
  SurveyMappingError,
  toDraftQuestions,
  toResultSnapshot,
  toRunSnapshot,
  toSaveBody,
  toTextAnswerPage,
  toWireAnswers,
} from '../../mapper';
import type { SurveyQuestionWire, SurveyResultWire, SurveyRunWire, SurveyTextPageWire } from '../../types';

const WIRE_TYPES = [
  ['SINGLE_CHOICE', 'single'],
  ['MULTIPLE_CHOICE', 'multi'],
  ['RATING', 'rating'],
  ['SHORT_TEXT', 'short_text'],
  ['LONG_TEXT', 'long_text'],
  ['APPLICATION', 'application'],
] as const;

function question(over: Partial<SurveyQuestionWire> = {}): SurveyQuestionWire {
  return {
    questionId: 101,
    type: 'SINGLE_CHOICE',
    prompt: '우리 부스를 어떻게 알았나요?',
    required: true,
    order: 0,
    options: [
      { optionId: 1001, label: '월드를 돌아다니다가' },
      { optionId: 1002, label: '추천을 받고' },
    ],
    scale: null,
    ...over,
  };
}

describe('문항 유형 매핑', () => {
  it.each(WIRE_TYPES)('%s ↔ %s 를 왕복한다', (wire, vm) => {
    const [mapped] = toDraftQuestions([question({ type: wire, options: [], scale: null })]);
    expect(mapped.type).toBe(vm as SurveyQuestionType);
    const [back] = toSaveBody({ title: 't', questions: [{ ...mapped, options: undefined }] }).questions;
    expect(back.type).toBe(wire);
  });

  // 조용히 버리면 문항 수가 말없이 줄어 "3문항 중 2개" 가 그려지고 그 사실이 어디에도 안 남는다
  it('계약 밖 유형은 버리지 않고 던진다', () => {
    expect(() => toDraftQuestions([question({ type: 'MATRIX' })])).toThrow(SurveyMappingError);
  });
});

describe('toRunSnapshot (§5)', () => {
  const run: SurveyRunWire = {
    surveyId: 12,
    closed: false,
    rewardCoin: 5,
    questions: [question(), question({ questionId: 102, type: 'RATING', options: [], scale: { min: 1, max: 5 } })],
  };

  it('closed → status, rewardCoin·surveyId 를 그대로 싣는다', () => {
    expect(toRunSnapshot(run)).toMatchObject({ surveyId: 12, status: 'open', rewardCoin: 5 });
    expect(toRunSnapshot({ ...run, closed: true }).status).toBe('closed');
  });

  it('run 경로 문항 id 는 서버 id 다 — 제출이 이 값을 되돌려 쓴다', () => {
    const [first, second] = toRunSnapshot(run).questions;
    expect(first.id).toBe('101');
    expect(first.options?.map((o) => o.id)).toEqual(['1001', '1002']);
    // 선택형이 아니면 options 는 빈 배열, 별점이 아니면 scale 은 null 로 온다 — VM 에서는 키를 뺀다
    expect(second.options).toBeUndefined();
    expect(second.scale).toEqual({ min: 1, max: 5 });
  });

  it('draft 경로 문항 id 는 로컬로 재발급한다 — PUT 이 id 를 받지 않는다', () => {
    const [first] = toDraftQuestions(run.questions);
    expect(first.id).toBe('q-1');
    expect(first.options?.map((o) => o.id)).toEqual(['o-1', 'o-2']);
  });
});

describe('toSaveBody (§4)', () => {
  const draft: SurveyDraftVM = {
    title: 'A604 부스 설문',
    questions: [
      { id: 'q-1', type: 'single', prompt: '어떻게 알았나요?', required: true, options: [{ id: 'o-1', label: '월드' }] },
      { id: 'q-2', type: 'rating', prompt: '만족도', required: true, scale: { min: 1, max: 5 } },
      { id: 'q-3', type: 'long_text', prompt: '개선점', required: false },
    ],
  };

  // 키가 없어야 서버가 기존 값을 유지한다. 값이 아니라 **키 부재**를 본다 (T-97 회귀)
  it('description·rewardCoin·closesAt 키를 싣지 않는다 — 실으면 저장할 때마다 보상이 지워진다', () => {
    const body = toSaveBody(draft) as unknown as Record<string, unknown>;
    expect(Object.keys(body)).toEqual(['title', 'questions']);
    expect('rewardCoin' in body).toBe(false);
    expect('description' in body).toBe(false);
    expect('closesAt' in body).toBe(false);
  });

  it('선택형이 아니면 options 키가, 별점이 아니면 scale 키가 없다', () => {
    const [choice, rating, text] = toSaveBody(draft).questions;
    expect(choice.options).toEqual([{ label: '월드' }]);
    expect('scale' in choice).toBe(false);
    expect(rating.scale).toEqual({ min: 1, max: 5 });
    expect('options' in rating).toBe(false);
    expect('options' in text).toBe(false);
    expect('scale' in text).toBe(false);
  });
});

describe('toWireAnswers (§6)', () => {
  it('맵을 배열로 바꾸고 유형별 키 하나만 채운다', () => {
    const answers: Record<string, SurveyAnswerValue> = {
      '101': { type: 'single', optionId: '1001' },
      '102': { type: 'multi', optionIds: ['1003', '1004'] },
      '103': { type: 'rating', value: 4 },
      '104': { type: 'short_text', text: '좋았어요' },
    };
    expect(toWireAnswers(answers)).toEqual([
      { questionId: 101, selectedOptionIds: [1001] },
      { questionId: 102, selectedOptionIds: [1003, 1004] },
      { questionId: 103, rating: 4 },
      { questionId: 104, text: '좋았어요' },
    ]);
  });

  it('빈 선택·공백 텍스트는 아예 싣지 않는다 — 서버도 같은 판정이다', () => {
    expect(
      toWireAnswers({
        '101': { type: 'multi', optionIds: [] },
        '102': { type: 'long_text', text: '   ' },
        '103': { type: 'rating', value: 3 },
      }),
    ).toEqual([{ questionId: 103, rating: 3 }]);
  });

  // Builder 의 로컬 id 가 제출 경로로 새면 NaN 을 보내게 된다 — 그 자리에서 드러낸다
  it('로컬 id 가 섞이면 NaN 을 보내지 않고 던진다', () => {
    expect(() => toWireAnswers({ 'q-1': { type: 'rating', value: 3 } })).toThrow(SurveyMappingError);
    expect(() => toWireAnswers({ '101': { type: 'single', optionId: 'o-1' } })).toThrow(SurveyMappingError);
  });
});

describe('toResultSnapshot (§7)', () => {
  const result: SurveyResultWire = {
    surveyId: 12,
    totalResponses: 20,
    firstRespondedAt: '2026-09-08T04:11:02Z',
    lastRespondedAt: '2026-09-08T07:55:40Z',
    perQuestion: [
      { questionId: 101, type: 'SINGLE_CHOICE', answeredCount: 18, counts: [{ optionId: 1001, label: '월드', count: 11 }], average: null, distribution: [] },
      { questionId: 102, type: 'RATING', answeredCount: 20, counts: [], average: 4.2, distribution: [{ value: 5, count: 9 }] },
      { questionId: 103, type: 'LONG_TEXT', answeredCount: 12, counts: [], average: null, distribution: [] },
    ],
    textAnswers: { content: [{ responseId: 55, questionId: 103, text: '무대 일정 안내가…' }], page: 0, size: 20, totalElements: 12, totalPages: 1 },
  };

  // spec 010 US2 시나리오 1 이 "전체 응답 수·최초·최근 응답 시각" 표시를 인수 조건으로 요구한다
  it('최초·최근 응답 시각을 ISO 원형 그대로 옮긴다', () => {
    const s = toResultSnapshot(result);
    expect(s.firstRespondedAt).toBe('2026-09-08T04:11:02Z');
    expect(s.lastRespondedAt).toBe('2026-09-08T07:55:40Z');
  });

  it('응답 0건이면 두 시각이 null 로 남는다 — 상태에서 문자열로 치환하지 않는다', () => {
    const s = toResultSnapshot({ ...result, totalResponses: 0, firstRespondedAt: null, lastRespondedAt: null });
    expect(s.firstRespondedAt).toBeNull();
    expect(s.lastRespondedAt).toBeNull();
  });

  it('텍스트 유형은 거르고 answeredCount 를 싣는다 — 비율의 분모다', () => {
    const snapshot = toResultSnapshot(result);
    expect(snapshot.perQuestion.map((a) => a.questionId)).toEqual(['101', '102']);
    expect(snapshot.perQuestion[0]).toMatchObject({ kind: 'choice', answeredCount: 18 });
    expect(snapshot.perQuestion[1]).toMatchObject({ kind: 'rating', answeredCount: 20, average: 4.2 });
    expect(snapshot.totalResponses).toBe(20);
  });

  it('응답 0건이면 average 가 null 로 살아남는다 — 0 으로 나누지 않는다', () => {
    const empty = toResultSnapshot({
      ...result,
      totalResponses: 0,
      perQuestion: [{ questionId: 102, type: 'RATING', answeredCount: 0, counts: [], average: null, distribution: [] }],
    });
    expect(empty.perQuestion[0]).toMatchObject({ kind: 'rating', answeredCount: 0, average: null });
    expect(empty.totalResponses).toBe(0);
  });

  it('걸러진 텍스트 유형과 달리 모르는 유형은 던진다', () => {
    expect(() =>
      toResultSnapshot({ ...result, perQuestion: [{ ...result.perQuestion[0], type: 'MATRIX' }] }),
    ).toThrow(SurveyMappingError);
  });
});

describe('toTextAnswerPage (§8)', () => {
  const page = (over: Partial<SurveyTextPageWire>): SurveyTextPageWire => ({
    // 문항 두 개를 섞는다 — 하나뿐이면 questionId 유실이 드러나지 않는다
    content: [
      { responseId: 1, questionId: 103, text: 'a' },
      { responseId: 2, questionId: 104, text: 'b' },
    ],
    page: 0,
    size: 2,
    totalElements: 5,
    totalPages: 3,
    ...over,
  });

  it('hasNext 는 page + 1 < totalPages 다', () => {
    expect(toTextAnswerPage(page({})).hasNext).toBe(true);
    expect(toTextAnswerPage(page({ page: 2 })).hasNext).toBe(false);
  });

  // text 만 남기면 텍스트 문항이 둘 이상인 설문에서 어느 질문의 답인지 복구할 수 없다 (#133)
  it('questionId 를 버리지 않는다 — 서버 wire id 를 string 으로 담는다', () => {
    expect(toTextAnswerPage(page({})).items).toEqual([
      { questionId: '103', text: 'a' },
      { questionId: '104', text: 'b' },
    ]);
  });
});

// ---------------------------------------------------------------------------
// 어댑터 — 경로와 두 이음매(404→null · booth→survey)만 본다
// ---------------------------------------------------------------------------

type Call = { url: string; init: RequestInit | undefined };

function stubFetch(responder: (url: string) => { status?: number; body: unknown }): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    calls.push({ url, init });
    const { status = 200, body } = responder(url);
    return {
      ok: status >= 200 && status < 300,
      status,
      headers: new Headers(),
      json: async () => body,
    } as unknown as Response;
  });
  return calls;
}

const RUN_BODY: SurveyRunWire = { surveyId: 12, closed: false, rewardCoin: 5, questions: [] };
const SURVEY_BODY = { surveyId: 12, boothId: 7, title: 'A604', description: null, rewardCoin: 5, closesAt: null, closed: false, responseCount: 0, questions: [] };
const RESULT_BODY: SurveyResultWire = {
  surveyId: 12,
  totalResponses: 0,
  firstRespondedAt: null,
  lastRespondedAt: null,
  perQuestion: [],
  textAnswers: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
};

beforeEach(() => {
  setAccessToken('test-token');
});

afterEach(() => {
  vi.unstubAllGlobals();
  setAccessToken(null);
});

describe('surveyHttpPort', () => {
  it('getRun 은 부스 기준 경로를 부른다', async () => {
    const calls = stubFetch(() => ({ body: RUN_BODY }));
    await surveyHttpPort.getRun(7);
    expect(calls[0].url).toContain('/api/v1/booths/7/survey/run');
  });

  it('getDraft 는 SURVEY_NOT_FOUND 404 를 null 로 옮긴다 — 오류가 아니라 정상 상태다', async () => {
    stubFetch(() => ({ status: 404, body: { code: 'SURVEY_NOT_FOUND', message: '없음', errors: [], warnings: [] } }));
    await expect(surveyHttpPort.getDraft(7)).resolves.toBeNull();
  });

  it('같은 404 라도 BOOTH_NOT_FOUND 는 그대로 던진다 — 그 구별이 이 처리의 존재 이유다', async () => {
    stubFetch(() => ({ status: 404, body: { code: 'BOOTH_NOT_FOUND', message: '없음', errors: [], warnings: [] } }));
    await expect(surveyHttpPort.getDraft(7)).rejects.toMatchObject({ code: 'BOOTH_NOT_FOUND' });
  });

  it('saveDraft 는 PUT 본문에 title·questions 만 싣는다', async () => {
    const calls = stubFetch(() => ({ body: SURVEY_BODY }));
    await surveyHttpPort.saveDraft(7, { title: 'A604', questions: [] });
    expect(calls[0].init?.method).toBe('PUT');
    expect(Object.keys(JSON.parse(String(calls[0].init?.body)))).toEqual(['title', 'questions']);
  });

  it('submitAnswers 는 surveyId 경로로 POST 하고 지급 코인을 돌려준다', async () => {
    const calls = stubFetch(() => ({ body: { responseId: 55, rewardedCoin: 5 } }));
    await expect(surveyHttpPort.submitAnswers(12, { '101': { type: 'rating', value: 4 } })).resolves.toEqual({
      rewardedCoin: 5,
    });
    expect(calls[0].url).toContain('/api/v1/surveys/12/responses');
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ answers: [{ questionId: 101, rating: 4 }] });
  });

  // 부스 기준 결과 경로가 계약에 없어 어댑터가 surveyId 를 먼저 해석한다. BE 에 별칭을 요청해
  // 뒀지만 확정 전이라 여기서 가정하지 않는다 — 별칭이 생기면 바뀌는 곳은 이 한 함수다
  it('getResult 는 부스로 설문을 찾은 뒤 결과를 부른다', async () => {
    const calls = stubFetch((url) => ({ body: url.includes('/results') ? RESULT_BODY : SURVEY_BODY }));
    await surveyHttpPort.getResult(7);
    expect(calls.map((c) => c.url.replace(/^https?:\/\/[^/]+/, ''))).toEqual([
      '/api/v1/booths/7/survey',
      '/api/v1/surveys/12/results',
    ]);
  });

  it('getTextAnswers 는 page 만 보낸다 — size 는 서버 기본값을 쓴다', async () => {
    const calls = stubFetch(() => ({ body: { content: [], page: 1, size: 20, totalElements: 0, totalPages: 2 } }));
    await surveyHttpPort.getTextAnswers(12, 1);
    expect(calls[0].url).toContain('/api/v1/surveys/12/text-answers?page=1');
    expect(calls[0].url).not.toContain('size=');
  });
});
