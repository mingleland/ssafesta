// Survey wire ↔ VM 변환 (S15P21A604-528, 계약 §9 "FE 어댑터가 옮길 것").
//
// ## id 공간이 둘이다
//
//   서버 id 공간   run 경로. 서버가 준 questionId·optionId 를 String() 으로 담아 두고
//                 제출할 때 Number() 로 되돌린다. surveyId 는 VM 에서도 number 그대로다
//   FE 로컬 id     draft 경로. Builder 는 서버 id 가 존재하기 전에 q-1·o-1 을 스스로 채번한다
//                 (builder.ts 가 ^q-(\d+)$ 로 seq 를 되살린다). PUT 은 애초에 id 를 받지 않는다
//
// 그래서 문항·선택지 id 는 VM 에서 string 이고, surveyId 만 number 다. 두 공간이 조용히 섞이지
// 않도록 toWireAnswers 가 Number() 결과를 검사해 NaN 이면 던진다.
import type {
  SurveyAnswerValue,
  SurveyDraftVM,
  SurveyQuestionAggregateVM,
  SurveyQuestionType,
  SurveyQuestionVM,
} from '../../shared/contracts/survey';
import type { SurveyResultSnapshot, SurveyRunSnapshot, SurveyTextAnswerPage } from './api.port';
import type {
  SurveyAggregateWire,
  SurveyAnswerWire,
  SurveyQuestionWire,
  SurveyResultWire,
  EventSurveyRunWire,
  SurveyRunWire,
  SurveySaveQuestionWire,
  SurveySaveWire,
  SurveyTextPageWire,
  SurveyWire,
  SurveyWireQuestionType,
} from './types';

/** 계약이 아는 값이 아니다 — 조용히 넘기지 않고 이 오류로 드러낸다 */
export class SurveyMappingError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SurveyMappingError';
  }
}

// Record 로 선언해 6쌍이 빠짐없이 대응하는지 컴파일러가 검사하게 한다.
const TYPE_FROM_WIRE: Record<SurveyWireQuestionType, SurveyQuestionType> = {
  SINGLE_CHOICE: 'single',
  MULTIPLE_CHOICE: 'multi',
  RATING: 'rating',
  SHORT_TEXT: 'short_text',
  LONG_TEXT: 'long_text',
  APPLICATION: 'application',
};

const TYPE_TO_WIRE: Record<SurveyQuestionType, SurveyWireQuestionType> = {
  single: 'SINGLE_CHOICE',
  multi: 'MULTIPLE_CHOICE',
  rating: 'RATING',
  short_text: 'SHORT_TEXT',
  long_text: 'LONG_TEXT',
  application: 'APPLICATION',
};

/**
 * wire 유형 → FE 유형. **모르는 값은 던진다.**
 *
 * 버리면 문항 수가 말없이 줄어 화면이 "3문항 중 2개" 를 그리고, 그것이 응답 0인지 문항 삭제인지
 * 구별되지 않는다 — 계약 §7 이 perQuestion 에 전 문항을 싣는 이유와 같은 판단이다.
 */
export function toQuestionType(wire: string): SurveyQuestionType {
  const mapped = TYPE_FROM_WIRE[wire as SurveyWireQuestionType];
  if (mapped === undefined) throw new SurveyMappingError(`알 수 없는 문항 유형입니다: ${wire}`);
  return mapped;
}

function toQuestion(wire: SurveyQuestionWire, id: string, optionId: (index: number) => string): SurveyQuestionVM {
  const type = toQuestionType(wire.type);
  const question: SurveyQuestionVM = { id, type, prompt: wire.prompt, required: wire.required };
  // 선택형이 아니면 options 는 빈 배열, 별점이 아니면 scale 은 null 로 온다 (§2) — VM 에서는 키를 뺀다
  if (wire.options.length > 0) {
    question.options = wire.options.map((o, i) => ({ id: optionId(i), label: o.label }));
  }
  if (wire.scale !== null) question.scale = { ...wire.scale };
  return question;
}

/** run 경로 — 서버 id 를 보존한다. 제출이 이 id 를 되돌려 쓴다 */
export function toRunQuestions(wires: SurveyQuestionWire[]): SurveyQuestionVM[] {
  return wires.map((w) => toQuestion(w, String(w.questionId), (i) => String(w.options[i].optionId)));
}

/**
 * draft 경로 — 로컬 id 로 **재발급**한다.
 *
 * PUT 이 id 를 받지 않으므로 서버 id 를 왕복시킬 이유가 없고, Builder 의 q-N 채번 규칙이
 * 살아 있어야 재로드 후 id 충돌이 나지 않는다 (S15P21A604-377 회귀).
 */
export function toDraftQuestions(wires: SurveyQuestionWire[]): SurveyQuestionVM[] {
  return wires.map((w, qi) => toQuestion(w, `q-${qi + 1}`, (oi) => `o-${oi + 1}`));
}

export function toRunSnapshot(wire: SurveyRunWire): SurveyRunSnapshot {
  return {
    surveyId: wire.surveyId,
    status: wire.closed ? 'closed' : 'open',
    rewardCoin: wire.rewardCoin,
    // 부스 설문에서 회원 전용은 보상이 있을 때뿐이다 — 그 판정은 rewardCoin 이 이미 싣고 있어서
    // 여기서 다시 말하지 않는다. 참여 이력은 부스 계약에 없다(재참여는 409 가 막는다)
    memberOnly: false,
    responded: null,
    questions: toRunQuestions(wire.questions),
  };
}

/**
 * 이벤트 설문 run (S15P21A604-608). 부스 경로와 **같은 스냅샷**으로 접는다 — 상태 기계와 화면이
 * 두 경로를 구별하지 않는 것이 이 설계의 요점이라, 차이는 여기서 끝난다.
 */
export function toEventRunSnapshot(wire: EventSurveyRunWire): SurveyRunSnapshot {
  return {
    surveyId: wire.surveyId,
    status: wire.closed ? 'closed' : 'open',
    rewardCoin: wire.rewardCoin,
    memberOnly: wire.memberOnly,
    responded: wire.responded,
    questions: toRunQuestions(wire.questions),
  };
}

export function toDraft(wire: SurveyWire): SurveyDraftVM {
  return { title: wire.title, questions: toDraftQuestions(wire.questions) };
}

/**
 * §4 PUT 본문. **description·rewardCoin·closesAt 키를 싣지 않는다** — 계약이 키 존재 여부로
 * 판정하므로, FE 가 그 값을 편집하지 않는 지금 키를 실으면 저장할 때마다 보상이 지워진다(T-97 자리).
 */
export function toSaveBody(draft: SurveyDraftVM): SurveySaveWire {
  return {
    title: draft.title,
    questions: draft.questions.map((q): SurveySaveQuestionWire => {
      const wire: SurveySaveQuestionWire = {
        type: TYPE_TO_WIRE[q.type],
        prompt: q.prompt,
        required: q.required,
      };
      // 선택형이 아니면 options 키가, RATING 이 아니면 scale 키가 있으면 안 된다 (§4 검증)
      if (q.type === 'single' || q.type === 'multi') {
        wire.options = (q.options ?? []).map((o) => ({ label: o.label }));
      }
      if (q.type === 'rating' && q.scale !== undefined) wire.scale = { ...q.scale };
      return wire;
    }),
  };
}

/**
 * 키 존재만으로는 응답이 아니다 — 빈 선택·공백 텍스트는 미응답이다 (S15P21A604-377).
 * 계약 §6 이 서버도 같은 판정이라고 명시하므로 정의는 이 한 곳에 둔다.
 */
export function isEmptyAnswer(value: SurveyAnswerValue): boolean {
  switch (value.type) {
    case 'multi':
      return value.optionIds.length === 0;
    case 'short_text':
    case 'long_text':
    case 'application':
      return value.text.trim() === '';
    default:
      return false;
  }
}

function toNumericId(id: string, what: string): number {
  const parsed = Number(id);
  if (!Number.isFinite(parsed)) {
    throw new SurveyMappingError(`${what} 가 서버 id 가 아닙니다: ${id}`);
  }
  return parsed;
}

/** 맵 → 배열. 미응답은 아예 싣지 않는다 (§6 "배열에서 빼거나 빈 값으로 보낸다") */
export function toWireAnswers(answers: Record<string, SurveyAnswerValue>): SurveyAnswerWire[] {
  return Object.entries(answers)
    .filter(([, value]) => !isEmptyAnswer(value))
    .map(([id, value]) => {
      const questionId = toNumericId(id, '문항 id');
      switch (value.type) {
        case 'single':
          return { questionId, selectedOptionIds: [toNumericId(value.optionId, '선택지 id')] };
        case 'multi':
          return { questionId, selectedOptionIds: value.optionIds.map((o) => toNumericId(o, '선택지 id')) };
        case 'rating':
          return { questionId, rating: value.value };
        default:
          return { questionId, text: value.text };
      }
    });
}

// 계약 §9 가 명시적으로 허용한 제외 — 텍스트 3유형은 SurveyQuestionAggregateVM 에 자리가 없다.
// **이름으로 열거한다.** default 분기가 흡수하게 두면 모르는 유형까지 같이 사라진다.
const TEXT_AGGREGATE_TYPES: SurveyQuestionType[] = ['short_text', 'long_text', 'application'];

function toAggregate(wire: SurveyAggregateWire): SurveyQuestionAggregateVM | null {
  const type = toQuestionType(wire.type); // 모르는 유형이면 여기서 던진다
  if (TEXT_AGGREGATE_TYPES.includes(type)) return null;
  const questionId = String(wire.questionId);
  if (type === 'rating') {
    return {
      questionId,
      kind: 'rating',
      answeredCount: wire.answeredCount,
      average: wire.average,
      distribution: wire.distribution.map((d) => ({ ...d })),
    };
  }
  return {
    questionId,
    kind: 'choice',
    answeredCount: wire.answeredCount,
    counts: wire.counts.map((c) => ({ optionId: String(c.optionId), label: c.label, count: c.count })),
  };
}

export function toTextAnswerPage(wire: SurveyTextPageWire): SurveyTextAnswerPage {
  return {
    // questionId 를 버리지 않는다 — 서버가 이미 주는 값이고, 없으면 텍스트 문항이 여럿일 때
    // 어느 질문의 답인지 복구할 수 없다. run 경로 문항 id 와 같은 공간이 되도록 String() 을 쓴다
    items: wire.content.map((c) => ({ questionId: String(c.questionId), text: c.text })),
    page: wire.page,
    hasNext: wire.page + 1 < wire.totalPages,
  };
}

export function toResultSnapshot(wire: SurveyResultWire): SurveyResultSnapshot {
  const perQuestion: SurveyQuestionAggregateVM[] = [];
  for (const aggregate of wire.perQuestion) {
    const mapped = toAggregate(aggregate);
    if (mapped !== null) perQuestion.push(mapped);
  }
  return {
    surveyId: wire.surveyId,
    totalResponses: wire.totalResponses,
    // ISO 문자열 원형 그대로. 포맷은 UI 가 하고 null 은 null 로 둔다
    firstRespondedAt: wire.firstRespondedAt,
    lastRespondedAt: wire.lastRespondedAt,
    perQuestion,
    textAnswers: toTextAnswerPage(wire.textAnswers),
  };
}
