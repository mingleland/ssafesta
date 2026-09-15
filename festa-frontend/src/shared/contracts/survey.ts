// Survey 계약 정본 — Shared Contract Freeze Candidate v0.1 (2026-09-01, -377 정정).
// FE UX Contract 만 확정한다 — BE endpoint·DTO·DB·Unity 이벤트는 미확정(UNKNOWN)이며 여기 없다.
// 6유형은 spec 010 FR-002 전사: 객관식(single)·복수선택(multi)·별점(rating)·단답(short_text)·
// 장문(long_text)·지원서(application). BE(-130·-190) 착수 시 Adapter/Mapper 만 추가, 이 타입 불변이 목표.

export type SurveyQuestionType = 'single' | 'multi' | 'rating' | 'short_text' | 'long_text' | 'application';

export interface SurveyQuestionVM {
  id: string;
  type: SurveyQuestionType;
  prompt: string;
  required: boolean;
  /** single·multi 전용 */
  options?: { id: string; label: string }[];
  /** rating 전용 */
  scale?: { min: number; max: number };
}

export type SurveyAnswerValue =
  | { type: 'single'; optionId: string }
  | { type: 'multi'; optionIds: string[] }
  | { type: 'rating'; value: number }
  | { type: 'short_text'; text: string }
  | { type: 'long_text'; text: string }
  // 지원서 — 특별 처리(제출자별 상세 조회)는 C-03 미확정: 확정 전에는 장문 입력 계열로만 다룬다
  | { type: 'application'; text: string };

/**
 * 설문을 **어디서 찾는가**. 화면·상태 기계는 같고 이것 하나만 다르다 (S15P21A604-608).
 *
 * 부스 설문은 부스가 열쇠다 — Unity 가 boothId 만 주고 서버가 그 부스의 설문을 돌려준다(계약 §5).
 * 이벤트 설문은 부스에 속하지 않는다. 운영이 하나 만들고 축제 참가자 전체가 답하므로 열쇠는
 * `surveyKey` 이고, **가짜 boothId 를 만들지 않는다** — `requireVisitorVisible` 이 임대·게시를
 * 요구해서 없는 부스를 지어내면 조용히 404 가 된다.
 *
 * 두 경로가 오버레이를 나눠 갖지 않는 이유: 문항 렌더·검증·제출·상태 전이가 전부 같다. 다른 것이
 * 진입 열쇠뿐이면 그 차이는 payload 안에서 끝내는 것이 맞고, 그래야 문항 유형이 늘 때 한 곳만 고친다.
 */
export type SurveySource =
  | { kind: 'booth'; boothId: number }
  | { kind: 'event'; surveyKey: string };

/** 같은 설문을 가리키는가 — run 상태의 늦은 응답 가드가 쓴다 */
export function isSameSurveySource(a: SurveySource | null, b: SurveySource | null): boolean {
  if (a === null || b === null) return a === b;
  if (a.kind === 'booth' && b.kind === 'booth') return a.boothId === b.boothId;
  if (a.kind === 'event' && b.kind === 'event') return a.surveyKey === b.surveyKey;
  return false;
}

export type SurveyRunStatus = 'idle' | 'loading' | 'ready' | 'empty' | 'error' | 'closed';

export interface SurveyRunVM {
  status: SurveyRunStatus;
  questions: SurveyQuestionVM[];
  answers: Record<string, SurveyAnswerValue>;
  progress: { current: number; total: number };
  submit: { phase: 'idle' | 'submitting' | 'success' | 'error' };
}

export interface SurveyResultVM {
  status: 'idle' | 'loading' | 'ready' | 'empty' | 'error';
  perQuestion: SurveyQuestionAggregateVM[];
  textAnswers: { items: string[]; page: number; hasNext: boolean };
}

// answeredCount = **그 문항에 답한 응답 수**. 비율의 분모는 이것이다 — 서버는 비율을 주지 않고
// 화면이 count / answeredCount 로 계산한다(계약 §7). 전체 응답 수와 다를 수 있다: 선택 문항을
// 건너뛴 사람이 있기 때문이다. 복수선택은 합이 100% 를 넘고 그게 정상이다.
//
// 텍스트 3유형은 이 VM 에 자리가 없어 mapper 가 거른다(계약 §9 가 명시적으로 허용한 제외).
export type SurveyQuestionAggregateVM =
  | {
      questionId: string;
      kind: 'choice';
      answeredCount: number;
      counts: { optionId: string; label: string; count: number }[];
    }
  // FR-006 — 별점은 평균·분포를 함께 제공한다. 응답 0건이면 average 는 null 이다(0 으로 나누지 않는다)
  | {
      questionId: string;
      kind: 'rating';
      answeredCount: number;
      average: number | null;
      distribution: { value: number; count: number }[];
    };

export interface SurveyBuilderIssueVM {
  questionId?: string;
  message: string;
}

export interface SurveyDraftVM {
  title: string;
  /** 응답 보상 Coin. 0 = 보상 없음. 0 보다 크면 그 설문은 회원 전용이 된다(C-05, GitLab #133) */
  rewardCoin: number;
  questions: SurveyQuestionVM[];
}
