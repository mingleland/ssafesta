// 사람 상담을 **어디서 시작했는가** 를 담는 경계 (S15P21A604-416, spec 011 FR-005).
//
// 상담 요청이 어느 부스를 대상으로 하는지는 진입 지점이 안다. 그 사실을 상태 기계 안으로
// 가져오지 않고 여기서 한 번 감싸는 이유:
//
//   - MVP 진입점은 AI 대화 에스컬레이션 하나뿐이다. 그렇다고 `startFromAiChat()` 같은
//     이름으로 굳히면 나중에 상담 데스크(CONSULTATION_DESK)를 붙일 때 같은 흐름을 복제하게 된다.
//   - 반대로 상태 기계가 진입점을 알게 하면(예: aiConversationId 를 들고 있게 하면) 진입점이
//     늘 때마다 상태 기계가 바뀐다.
//
// 그래서 진입점은 이 Context 를 만드는 **producer** 이고, 상태 기계는 그것만 받는다.
//
// 확장 지점: 상담 데스크가 생기면 `source: 'CONSULTATION_DESK'` 로 같은 Context 를 만들어
// 같은 `requestConsultation` 을 부르면 된다 — 상태 기계·채널·HUD 는 그대로다.

/** 이 상담이 시작된 자리 */
export type ConsultationStartSource =
  /** AI 직원과 대화하다 사람 상담을 요청 (spec 011 FR-005 — MVP 유일 경로) */
  | 'AI_HANDOFF'
  /** 부스 상담 데스크에서 직접 요청 — 계약 확정 후 (S15P21A604-416 후속) */
  | 'CONSULTATION_DESK';

export interface ConsultationStartContext {
  /** 상담 대상 부스. AI_HANDOFF 에서는 대화 중이던 그 부스다 */
  boothId: number;
  source: ConsultationStartSource;
  /**
   * AI 대화 요약(Handoff Summary, spec 011). 직원 대기열 카드에 그대로 노출된다.
   * **required 로 만들지 않는다** — 데스크 진입에는 선행 대화가 없어 이 값이 존재할 수 없다.
   */
  handoffSummary?: string;
}

/** AI 대화 오버레이가 쓰는 producer. 대상 부스는 AI_AGENT_INTERACT 가 준 boothId 다 */
export function aiHandoffContext(boothId: number, handoffSummary?: string): ConsultationStartContext {
  return { boothId, source: 'AI_HANDOFF', handoffSummary };
}
