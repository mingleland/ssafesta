// 관리자 콘솔 계약의 FE 측 정본 (S15P21A604-828).
//
// 두 부류가 섞여 있다. **BE 구현이 정본인 것**(origin/develop backend user/Admin*Controller,
// wallet/AdminWalletController, booth/AdminBoothPublicationController, survey/AdminEventSurveyController)과
// **BE 가 아직 없어 FE 가 먼저 정한 것**이다. 후자는 각 항목에 `[FE contract]` 로 표시한다 —
// BE 가 도달하면 그 응답에 맞춰 real adapter(api.ts)만 고치고 화면은 건드리지 않는다.
import type { WalletTransaction } from '../wallet/types';

/**
 * 내가 관리자인가. [FE contract] — `GET /users/me` 에 아직 이 두 칸이 없다. real adapter 는
 * 응답에 칸이 있으면 읽고 없으면 false 로 둔다. **FE 가드는 UX 보조일 뿐이고 최종 판정은 매 요청
 * BE(AdminGuard)가 한다** — 토큰의 role 은 관리자도 MEMBER 라 FE 가 스스로 알 길이 없다.
 */
export interface AdminCapability {
  admin: boolean;
  master: boolean;
}

/** GET /api/v1/admin/admins 원소 */
export interface AdminView {
  userId: number;
  nickname: string;
  /** 보호된 계정 — 강등·정지·코인 조정·부스 조치의 대상이 될 수 없다(MASTER_PROTECTED) */
  master: boolean;
}

export type AccountStatus = 'ACTIVE' | 'SUSPENDED';

/**
 * 회원 검색 결과 한 행. [FE contract] — BE 에 관리자용 회원 검색·상세가 없다(-742 기능 7·8 제외).
 * 기대 경로: `GET /api/v1/admin/users?query=&page=&size=` · `GET /api/v1/admin/users/{userId}`
 */
export interface AdminMemberView {
  userId: number;
  nickname: string;
  status: AccountStatus | string;
  admin: boolean;
  master: boolean;
  providers: string[];
  joinedAt: string | null;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** GET /api/v1/admin/users/{userId}/status-history 원소 (AccountStatusHistoryView) */
export interface AccountStatusHistoryView {
  userId: number;
  previousStatus: AccountStatus | string;
  currentStatus: AccountStatus | string;
  reason: string | null;
  /** 0 = 시스템(마이그레이션) */
  actorUserId: number;
  createdAt: string;
}

/** GET /api/v1/admin/wallets/{userId} */
export interface AdminBalance {
  userId: number;
  balance: number;
  updatedAt: string;
}

/** GET /api/v1/admin/wallets/{userId}/ledger 원소 — 회원 본인이 보는 원장과 같은 모양(CoinLedgerEntryView) */
export type AdminLedgerEntry = WalletTransaction;

/** POST /api/v1/admin/wallets/{userId}/adjustments 응답 */
export interface AdjustmentResult {
  userId: number;
  entryId: number;
  balanceAfter: number;
  /** 같은 Idempotency-Key 로 이미 처리된 요청 — 이번에는 아무 일도 하지 않았다 */
  alreadyApplied: boolean;
}

/**
 * 부스 한 칸. GET /booth-slots(공개)에서 OCCUPIED 만 추린 것이다 — 관리자 전용 부스 목록 API 는 없고
 * 강제 비공개(POST /admin/booths/{boothId}/unpublish)만 있다. `entryAvailable` 을 "게시됨" 의
 * 근사치로 쓴다(게시본이 있어야 입장이 열린다).
 */
export interface AdminBoothView {
  slotId: number;
  slotCode: string;
  boothId: number;
  boothName: string | null;
  entryAvailable: boolean;
  leaseEndsAt: string | null;
}

// ── 이벤트 상점 [FE contract 전부] — BE 에 경품·구매 도메인이 아직 없다 ──────────────────────
//
// 코인 차감(원장)과 경품 지급은 **다른 사건**이다. 원장에 SPEND 가 남았다고 물건을 받은 것이 아니다.
// 그래서 구매 한 건이 `ledgerEntryId`(돈) 와 `fulfillment`(물건) 를 따로 든다.

/** 지급 처리 상태. 실제 지급 방식(현장 수령·추첨·사후 지급)이 갈리면 여기에 값이 늘어난다 */
export type PrizeFulfillmentStatus = 'PURCHASED' | 'PENDING' | 'FULFILLED' | 'CANCELLED';

/**
 * 지금 상태에서 갈 수 있는 다음 상태 — BE `PurchaseFulfillment.canTransitionTo` 와 같은 표다
 * (S15P21A604-853, GitLab #217 4번). 화면과 mock 이 같은 것을 봐야 mock 에서만 도는 흐름이 없다.
 *
 * **`FULFILLED` 와 `CANCELLED` 는 둘 다 종단이다.** 물건이 나갔거나 주문이 무효가 된 뒤에는 이
 * 상태를 되감지 않고, 잘못은 코인 조정과 메모로 바로잡는다(BE 판단). 여기서 열어 두면 누를 수 있는
 * 버튼이 서버에서만 409 로 거절당한다.
 */
export function nextFulfillmentOptions(current: PrizeFulfillmentStatus): PrizeFulfillmentStatus[] {
  switch (current) {
    case 'PURCHASED': return ['PENDING', 'FULFILLED', 'CANCELLED'];
    case 'PENDING': return ['FULFILLED', 'CANCELLED'];
    case 'FULFILLED': return [];
    case 'CANCELLED': return [];
  }
}

export interface PrizeView {
  prizeId: number;
  name: string;
  priceCoin: number;
  /** null = 무제한 */
  stock: number | null;
  active: boolean;
}

export interface PrizePurchaseView {
  purchaseId: number;
  prizeId: number;
  prizeName: string;
  buyerUserId: number;
  buyerNickname: string;
  quantity: number;
  coinSpent: number;
  /** 원장 항목. null 이면 코인 차감이 확인되지 않은 것이다 */
  ledgerEntryId: number | null;
  purchasedAt: string;
  fulfillment: PrizeFulfillmentStatus;
  /** 마지막 처리 메모 */
  note: string | null;
  updatedAt: string;
  /** 받는 자 정보 (GitLab #239, S15P21A604-912). #239 이전 행은 세 값이 함께 null 이다 */
  campus: string | null;
  teamName: string | null;
  recipientName: string | null;
}

// ── 이벤트 설문 ─────────────────────────────────────────────────────────────────────────

/** GET /api/v1/admin/event-surveys/{key}/entrants 원소 (EventEntrantView) — BE 정본 */
export interface EventEntrantView {
  responseId: number;
  userId: number;
  nickname: string;
  submittedAt: string;
}

/**
 * 공식 설문 요약. [FE contract] 목록 API 가 없다. real adapter 는 알려진 key 로
 * `GET /event-surveys/{key}/run`(BE 정본)과 entrants 첫 페이지를 합쳐 만든다.
 */
export interface EventSurveySummary {
  surveyKey: string;
  surveyId: number;
  title: string;
  closed: boolean;
  rewardCoin: number;
  questionCount: number;
  entrantCount: number;
}

export type EventQuestionType = 'SINGLE_CHOICE' | 'MULTI_CHOICE' | 'RATING' | 'TEXT' | string;

/** 질문별 집계. [FE contract] */
export interface EventQuestionAggregate {
  questionId: number;
  prompt: string;
  type: EventQuestionType;
  answered: number;
  /** 선택형·척도형 — 보기별 응답 수 */
  options: { label: string; count: number }[];
  /** 서술형 — 최근 응답 일부 */
  textSamples: string[];
}

/** 개별 응답. [FE contract] */
export interface EventResponseDetail {
  responseId: number;
  userId: number;
  nickname: string;
  submittedAt: string;
  answers: { questionId: number; prompt: string; value: string }[];
}

/**
 * 콘솔이 쓰는 저장소 계약. real(api.ts)·mock(api.mock.ts) 이 둘 다 이 모양이라 화면은 어느 쪽인지 모른다.
 * 새 기능이 BE 없이 들어올 때도 여기 시그니처를 먼저 세우고 mock 을 채운다 — UI 에 분기가 퍼지지 않게.
 */
export interface AdminRepository {
  getCapability(): Promise<AdminCapability>;

  listAdmins(): Promise<AdminView[]>;
  promoteAdmin(userId: number, note?: string): Promise<AdminView>;
  demoteAdmin(userId: number, note?: string): Promise<void>;

  searchMembers(query: string, page: number, size: number): Promise<Page<AdminMemberView>>;
  getMember(userId: number): Promise<AdminMemberView>;
  suspendMember(userId: number, reason: string): Promise<void>;
  unsuspendMember(userId: number): Promise<void>;
  getStatusHistory(userId: number, page: number, size: number): Promise<Page<AccountStatusHistoryView>>;

  getBalance(userId: number): Promise<AdminBalance>;
  getLedger(userId: number, page: number, size: number): Promise<Page<AdminLedgerEntry>>;
  adjustCoins(userId: number, idempotencyKey: string, signedAmount: number, note?: string): Promise<AdjustmentResult>;

  listBooths(): Promise<AdminBoothView[]>;
  unpublishBooth(boothId: number, reason: string): Promise<void>;

  listPrizes(): Promise<PrizeView[]>;
  listPurchases(status: PrizeFulfillmentStatus | 'ALL', page: number, size: number): Promise<Page<PrizePurchaseView>>;
  updateFulfillment(purchaseId: number, status: PrizeFulfillmentStatus, note?: string): Promise<PrizePurchaseView>;

  listEventSurveys(): Promise<EventSurveySummary[]>;
  getEventSurvey(surveyKey: string): Promise<EventSurveySummary>;
  listEntrants(surveyKey: string, page: number, size: number): Promise<Page<EventEntrantView>>;
  getEventAggregate(surveyKey: string): Promise<EventQuestionAggregate[]>;
  getEventResponse(surveyKey: string, responseId: number): Promise<EventResponseDetail>;
}

