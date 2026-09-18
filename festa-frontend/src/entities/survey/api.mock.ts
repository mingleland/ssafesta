// Survey mock 어댑터 — 실 어댑터(api.ts)와 나란히 선 두 구현 중 하나다 (VITE_USE_MOCK 로 갈린다).
//
// 시나리오는 **부스 번호**로 가른다. 예전에는 surveyId 문자열('empty'·'closed'…)이었는데, 실 계약에서
// surveyId 가 서버가 주는 number 가 되면서 그 자리가 없어졌다 — 진입이 boothId 이므로 여기로 옮겼다.
// 매직 값을 테스트가 다시 쓰지 않도록 상수로 내보낸다.
import type { ApiError } from '../../shared/api/client';
import { EVENT_SURVEY_KEY } from '../../shared/contracts/survey';
import type { SurveyAnswerValue, SurveyDraftVM } from '../../shared/contracts/survey';
import type {
  SurveyPort,
  SurveyResultSnapshot,
  SurveyRunSnapshot,
  SurveySubmitResult,
  SurveyTextAnswerPage,
} from './api.port';
import { RUN_QUESTIONS } from './fixtures/run';
import { EVENT_RUN_QUESTIONS } from './fixtures/eventRun';
import { RESULT_AGGREGATES, TEXT_ANSWERS, TEXT_PAGE_SIZE } from './fixtures/result';

/** 평범한 설문이 있는 부스 */
export const MOCK_BOOTH_NORMAL = 1;
/** 아직 설문을 만들지 않은 부스 — getDraft 가 null, run 이 문항 0 */
export const MOCK_BOOTH_NEW = 2;
/** 문항 0 · 응답 0 */
export const MOCK_BOOTH_EMPTY = 3;
/** 마감된 설문 */
export const MOCK_BOOTH_CLOSED = 4;
/** 제출이 실패하는 부스 */
export const MOCK_BOOTH_SUBMIT_FAIL = 5;
/** 결과 조회가 실패하는 부스 */
export const MOCK_BOOTH_RESULT_FAIL = 6;
/** 보상이 걸린 설문 — 게스트 차단 안내 확인용 */
export const MOCK_BOOTH_REWARDED = 7;
/**
 * 마감 + 보상이 함께 걸린 설문. 둘이 겹쳐야 드러나는 자리가 있다 —
 * 마감인데 "참여하면 N 코인을 받습니다" 가 남는 결함(S15P21A604-541 F-3)이 그것이다.
 */
export const MOCK_BOOTH_CLOSED_REWARDED = 8;

/** 부스마다 surveyId 를 하나씩 준다 — 실 서버처럼 run 응답이 id 를 알려주는 흐름을 흉내낸다 */
const surveyIdOf = (boothId: number): number => 1000 + boothId;

// ── 이벤트 설문 (S15P21A604-608) ────────────────────────────────────────────
// 부스가 아니라 key 로 가른다. 부스 시나리오 상수를 재사용하지 않는다 — 이벤트 설문은 부스에
// 속하지 않는다는 것이 이 작업의 전제라, mock 에서라도 두 공간을 섞으면 그 전제가 흐려진다.

/**
 * 평범한 이벤트 설문 — 아직 참여하지 않았다.
 *
 * **앱이 실제로 쓰는 key 그 자체다** — 계약 정본(`shared/contracts/survey`)에서 가져온다.
 * mock 어댑터의 존재 이유가 BE 도달 전에 화면을 돌려 보는 것이라, 다른 key 를 주면 mock 모드에서
 * 설문이 404 로만 보이고 아무것도 검증하지 못한다. 값을 여기 다시 적지 않는 이유가 그것이다.
 */
export const MOCK_EVENT_SURVEY_KEY = EVENT_SURVEY_KEY;
/** 이미 참여한 이벤트 설문 */
export const MOCK_EVENT_SURVEY_DONE = 'SSAFESTA_MOCK_DONE';
/** 마감된 이벤트 설문 */
export const MOCK_EVENT_SURVEY_CLOSED = 'SSAFESTA_MOCK_CLOSED';

const EVENT_SURVEY_IDS: Record<string, number> = {
  [MOCK_EVENT_SURVEY_KEY]: 2001,
  [MOCK_EVENT_SURVEY_DONE]: 2002,
  [MOCK_EVENT_SURVEY_CLOSED]: 2003,
};

const MOCK_REWARD_COIN = 5;

function apiError(code: string, message: string, errors: ApiError['errors'] = []): ApiError {
  return { code, message, errors, warnings: [] };
}

/** 보상 상한 — 서버 `app.survey.*` 기본값 10 (GitLab #133). 기획 확정 시 값만 바뀐다 */
export const MOCK_REWARD_COIN_MAX = 10;

let submitted: Record<string, SurveyAnswerValue> | null = null;
let submittedSurveyId: number | null = null;
let savedDraft: { boothId: number; draft: SurveyDraftVM } | null = null;

export const surveyMockPort: SurveyPort = {
  async getRun(boothId: number): Promise<SurveyRunSnapshot> {
    const surveyId = surveyIdOf(boothId);
    const rewardCoin =
      boothId === MOCK_BOOTH_REWARDED || boothId === MOCK_BOOTH_CLOSED_REWARDED ? MOCK_REWARD_COIN : 0;
    // 부스 설문에서 회원 전용은 rewardCoin 이 이미 말한다. 참여 이력은 부스 계약에 없다
    const boothOnly = { memberOnly: false, responded: null } as const;
    if (boothId === MOCK_BOOTH_EMPTY || boothId === MOCK_BOOTH_NEW) {
      return { surveyId, status: 'open', rewardCoin, ...boothOnly, questions: [] };
    }
    if (boothId === MOCK_BOOTH_CLOSED || boothId === MOCK_BOOTH_CLOSED_REWARDED) {
      return { surveyId, status: 'closed', rewardCoin, ...boothOnly, questions: RUN_QUESTIONS };
    }
    return { surveyId, status: 'open', rewardCoin, ...boothOnly, questions: RUN_QUESTIONS };
  },

  async getEventRun(surveyKey: string): Promise<SurveyRunSnapshot> {
    const surveyId = EVENT_SURVEY_IDS[surveyKey];
    if (surveyId === undefined) throw apiError('SURVEY_NOT_FOUND', '설문을 찾을 수 없습니다.');
    return {
      surveyId,
      status: surveyKey === MOCK_EVENT_SURVEY_CLOSED ? 'closed' : 'open',
      // 이벤트 설문은 코인을 주지 않는다 — 참여 자체가 추첨 응모다. 보상 0 인데도 회원 전용인
      // 이유가 그것이고, 그래서 rewardCoin 과 memberOnly 가 별개 축이다
      rewardCoin: 0,
      memberOnly: true,
      responded:
        surveyKey === MOCK_EVENT_SURVEY_DONE
          ? { responseId: 9001, submittedAt: '2026-09-10T04:12:00Z' }
          : null,
      // 이벤트 설문은 확정 문항(V37 시드)을 쓴다 — 부스 6유형 데모와 다르다
      questions: EVENT_RUN_QUESTIONS,
    };
  },

  async submitAnswers(surveyId: number, answers: Record<string, SurveyAnswerValue>): Promise<SurveySubmitResult> {
    if (surveyId === surveyIdOf(MOCK_BOOTH_SUBMIT_FAIL)) throw apiError('UNKNOWN', '일시적인 오류입니다.');
    submitted = { ...answers };
    submittedSurveyId = surveyId;
    return { rewardedCoin: surveyId === surveyIdOf(MOCK_BOOTH_REWARDED) ? MOCK_REWARD_COIN : 0 };
  },

  async getResult(boothId: number): Promise<SurveyResultSnapshot> {
    const surveyId = surveyIdOf(boothId);
    if (boothId === MOCK_BOOTH_RESULT_FAIL) throw apiError('UNKNOWN', '일시적인 오류입니다.');
    if (boothId === MOCK_BOOTH_EMPTY || boothId === MOCK_BOOTH_NEW) {
      // 응답 0건이어도 문항은 다 실려 온다(계약 §7) — 빈 판정은 totalResponses 가 한다
      return {
        surveyId,
        totalResponses: 0,
        // 응답 0건이면 서버가 두 시각을 null 로 준다 (계약 §7)
        firstRespondedAt: null,
        lastRespondedAt: null,
        perQuestion: RESULT_AGGREGATES.map((a) =>
          a.kind === 'rating'
            ? { ...a, answeredCount: 0, average: null, distribution: a.distribution.map((d) => ({ ...d, count: 0 })) }
            : { ...a, answeredCount: 0, counts: a.counts.map((c) => ({ ...c, count: 0 })) },
        ),
        textAnswers: { items: [], page: 0, hasNext: false },
      };
    }
    return {
      surveyId,
      totalResponses: 20,
      firstRespondedAt: '2026-09-08T04:11:02Z',
      lastRespondedAt: '2026-09-08T07:55:40Z',
      perQuestion: RESULT_AGGREGATES,
      textAnswers: textPage(0),
    };
  },

  async getTextAnswers(_surveyId: number, page: number): Promise<SurveyTextAnswerPage> {
    return textPage(page);
  },

  async getDraft(boothId: number): Promise<SurveyDraftVM | null> {
    if (savedDraft === null || savedDraft.boothId !== boothId) return null;
    return structuredClone(savedDraft.draft);
  },

  // title 'FAIL' = 저장 실패 시나리오
  async saveDraft(boothId: number, draft: SurveyDraftVM): Promise<void> {
    if (draft.title === 'FAIL') throw apiError('UNKNOWN', '일시적인 오류입니다.');
    // 상한 초과는 서버가 400 VALIDATION_FAILED + errors[0].field 로 거절한다 (docs/08 §1.3-1)
    if (draft.rewardCoin > MOCK_REWARD_COIN_MAX) {
      throw apiError('VALIDATION_FAILED', '입력값을 확인해 주세요.', [
        { rule: 'FIELD_INVALID', field: 'rewardCoin', message: `보상 코인은 ${MOCK_REWARD_COIN_MAX} 이하여야 합니다.` },
      ]);
    }
    savedDraft = { boothId, draft: structuredClone(draft) };
  },
};

/** 어느 부스로 무엇이 저장됐는지 — boothId 가 Port 까지 실제로 갔는지 테스트가 본다 */
export function __savedDraftForTests(): { boothId: number; draft: SurveyDraftVM } | null {
  return savedDraft;
}

function textPage(page: number): SurveyTextAnswerPage {
  const start = page * TEXT_PAGE_SIZE;
  return {
    items: TEXT_ANSWERS.slice(start, start + TEXT_PAGE_SIZE),
    page,
    hasNext: start + TEXT_PAGE_SIZE < TEXT_ANSWERS.length,
  };
}

export function __submittedAnswersForTests(): Record<string, SurveyAnswerValue> | null {
  return submitted;
}

export function __submittedSurveyIdForTests(): number | null {
  return submittedSurveyId;
}

export function __resetSurveyMockForTests(): void {
  submitted = null;
  submittedSurveyId = null;
  savedDraft = null;
}
