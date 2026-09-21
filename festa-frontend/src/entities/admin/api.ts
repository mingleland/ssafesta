// 관리자 콘솔 real adapter — shared/api 의 api() 를 타므로 Authorization 이 자동으로 붙는다.
//
// 모든 endpoint 가 BE 정본과 연결돼 있다 — user/Admin*Controller·wallet/AdminWalletController·
// booth/AdminBoothPublicationController·survey/AdminEventSurveyController (#217 5건).
// 응답 모양이 바뀌면 이 파일의 매핑만 고친다 — 화면은 계약(types.ts)만 본다.
import { api, isApiError } from '../../shared/api/client';
import type { MyAccountResponse } from '../user/types';
import type {
  AccountStatusHistoryView,
  AdjustmentResult,
  AdminBalance,
  AdminBoothView,
  AdminCapability,
  AdminLedgerEntry,
  AdminMemberView,
  AdminRepository,
  AdminView,
  EventEntrantView,
  EventQuestionAggregate,
  EventResponseDetail,
  EventSurveySummary,
  Page,
  PrizeFulfillmentStatus,
  PrizePurchaseView,
  PrizeView,
  PrizeDraft,
} from './types';

const q = (s: string) => encodeURIComponent(s);


/**
 * 내가 관리자인가 — **관리자 API 에 직접 물어서** 답한다.
 *
 * `/users/me` 에는 이 답이 없다. 토큰의 `role` 도 관리자가 `MEMBER` 라(AdminGuard 의 의도된 설계)
 * FE 가 스스로 알 길이 없어서, `GET /admin/admins` 의 응답 자체를 근거로 쓴다 — 그 경로는
 * `AdminGuard.requireAdmin` 뒤에 있으므로 **200 이 온 것이 곧 관리자라는 서버 판정**이다.
 *
 * 회원 목록을 상시 긁지 않는다: 이 질의는 콘솔 가드와 진입 링크만 부르고, 관리자가 아니면 403 한 번으로
 * 끝난다. 403 은 401 인터셉트를 타지 않아 refresh 를 건드리지 않는다.
 *
 * `master` 는 그 목록에서 내 행을 찾아 읽는다. 목록에 내가 없어도(있을 수 없지만) 200 을 받은 사실은
 * 그대로 유효해 `admin` 은 참으로 둔다 — 판정의 근거는 목록이 아니라 서버가 통과시켰다는 것이다.
 *
 * `/users/me` 가 나중에 `admin`·`master` 를 실어 주면 그 값을 먼저 읽는다(`#217` 1번). 그때 이 왕복은
 * 사라진다.
 */
async function getCapability(): Promise<AdminCapability> {
  const me = await api<MyAccountResponse & { admin?: unknown; master?: unknown }>('/api/v1/users/me');
  if (typeof me.admin === 'boolean') return { admin: me.admin, master: me.master === true };
  try {
    const admins = await listAdmins();
    return { admin: true, master: admins.find((a) => a.userId === me.userId)?.master === true };
  } catch (error) {
    // 403 은 "관리자가 아니다" 라는 정상 답이다(FORBIDDEN·MEMBER_ONLY). 그 밖의 실패는 판정 불가라 던진다 —
    // 서버가 죽었는데 조용히 false 로 접으면 관리자에게 "권한 없음" 을 보여 주게 된다.
    if (isApiError(error) && error.status === 403) return { admin: false, master: false };
    throw error;
  }
}

function listAdmins(): Promise<AdminView[]> {
  return api<AdminView[]>('/api/v1/admin/admins');
}

function promoteAdmin(userId: number, note?: string): Promise<AdminView> {
  return api<AdminView>(`/api/v1/admin/admins/${userId}`, {
    method: 'POST',
    body: JSON.stringify(note === undefined ? {} : { note }),
  });
}

function demoteAdmin(userId: number, note?: string): Promise<void> {
  const qs = note === undefined ? '' : `?note=${q(note)}`;
  return api<void>(`/api/v1/admin/admins/${userId}${qs}`, { method: 'DELETE' });
}

function searchMembers(query: string, page: number, size: number): Promise<Page<AdminMemberView>> {
  return api<Page<AdminMemberView>>(`/api/v1/admin/users?query=${q(query)}&page=${page}&size=${size}`);
}

function getMember(userId: number): Promise<AdminMemberView> {
  return api<AdminMemberView>(`/api/v1/admin/users/${userId}`);
}

function suspendMember(userId: number, reason: string): Promise<void> {
  return api<void>(`/api/v1/admin/users/${userId}/suspend`, { method: 'POST', body: JSON.stringify({ reason }) });
}

function unsuspendMember(userId: number): Promise<void> {
  return api<void>(`/api/v1/admin/users/${userId}/unsuspend`, { method: 'POST' });
}

function getStatusHistory(userId: number, page: number, size: number): Promise<Page<AccountStatusHistoryView>> {
  return api<Page<AccountStatusHistoryView>>(`/api/v1/admin/users/${userId}/status-history?page=${page}&size=${size}`);
}

function getBalance(userId: number): Promise<AdminBalance> {
  return api<AdminBalance>(`/api/v1/admin/wallets/${userId}`);
}

function getLedger(userId: number, page: number, size: number): Promise<Page<AdminLedgerEntry>> {
  return api<Page<AdminLedgerEntry>>(`/api/v1/admin/wallets/${userId}/ledger?page=${page}&size=${size}`);
}

// Idempotency-Key 는 조정 한 건의 이름이다 — 재시도는 같은 값, 새 조정은 새 값 (features/admin/model/adjustmentKey)
function adjustCoins(userId: number, idempotencyKey: string, signedAmount: number, note?: string): Promise<AdjustmentResult> {
  return api<AdjustmentResult>(`/api/v1/admin/wallets/${userId}/adjustments`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ signedAmount, note }),
  });
}

// 전용 목록이다 — 공개 슬롯 목록을 걸러 만들던 근사치를 걷었다 (S15P21A604-951, GitLab #252).
// 정렬(slotCode 오름차순)도 서버가 한다.
function listBooths(): Promise<AdminBoothView[]> {
  return api<AdminBoothView[]>('/api/v1/admin/booths');
}

function unpublishBooth(boothId: number, reason: string): Promise<void> {
  return api<void>(`/api/v1/admin/booths/${boothId}/unpublish`, { method: 'POST', body: JSON.stringify({ reason }) });
}

// ── 이벤트 상점 — AdminEventShopController(S15P21A604-853 에 연결) ──
function listPrizes(): Promise<PrizeView[]> {
  return api<PrizeView[]>('/api/v1/admin/event-shop/prizes');
}

// 등록과 수정은 같은 본문을 쓴다 — 수정은 **덮어쓰기**라 폼이 현재 값을 채워 통째로 보낸다.
function createPrize(draft: PrizeDraft): Promise<PrizeView> {
  return api<PrizeView>('/api/v1/admin/event-shop/prizes', { method: 'POST', body: JSON.stringify(draft) });
}

function updatePrize(prizeId: number, draft: PrizeDraft): Promise<PrizeView> {
  return api<PrizeView>(`/api/v1/admin/event-shop/prizes/${prizeId}`, { method: 'PUT', body: JSON.stringify(draft) });
}

function listPurchases(status: PrizeFulfillmentStatus | 'ALL', page: number, size: number, won?: boolean | null): Promise<Page<PrizePurchaseView>> {
  const filter = status === 'ALL' ? '' : `&status=${status}`;
  // 보내지 않는 것과 false 를 보내는 것이 다르다 — 전자는 전체, 후자는 낙첨만이다.
  const draw = won === undefined || won === null ? '' : `&won=${String(won)}`;
  return api<Page<PrizePurchaseView>>(`/api/v1/admin/event-shop/purchases?page=${page}&size=${size}${filter}${draw}`);
}

function updateFulfillment(purchaseId: number, status: PrizeFulfillmentStatus, note?: string): Promise<PrizePurchaseView> {
  return api<PrizePurchaseView>(`/api/v1/admin/event-shop/purchases/${purchaseId}/fulfillment`, {
    method: 'POST',
    body: JSON.stringify({ status, note }),
  });
}

// ── 이벤트 설문 — 목록·요약·집계·개별 응답 모두 BE 정본(AdminEventSurveyController, #217 5번) ──
function listEventSurveys(): Promise<EventSurveySummary[]> {
  return api<EventSurveySummary[]>('/api/v1/admin/event-surveys');
}

async function getEventSurvey(surveyKey: string): Promise<EventSurveySummary> {
  const list = await listEventSurveys();
  const found = list.find((survey) => survey.surveyKey === surveyKey);
  if (found === undefined) throw new Error(`이벤트 설문을 찾을 수 없습니다: ${surveyKey}`);
  return found;
}

function listEntrants(surveyKey: string, page: number, size: number): Promise<Page<EventEntrantView>> {
  return api<Page<EventEntrantView>>(`/api/v1/admin/event-surveys/${q(surveyKey)}/entrants?page=${page}&size=${size}`);
}

function getEventAggregate(surveyKey: string): Promise<EventQuestionAggregate[]> {
  return api<EventQuestionAggregate[]>(`/api/v1/admin/event-surveys/${q(surveyKey)}/aggregate`);
}

function getEventResponse(surveyKey: string, responseId: number): Promise<EventResponseDetail> {
  return api<EventResponseDetail>(`/api/v1/admin/event-surveys/${q(surveyKey)}/responses/${responseId}`);
}

export const adminApi: AdminRepository = {
  getCapability,
  listAdmins,
  promoteAdmin,
  demoteAdmin,
  searchMembers,
  getMember,
  suspendMember,
  unsuspendMember,
  getStatusHistory,
  getBalance,
  getLedger,
  adjustCoins,
  listBooths,
  unpublishBooth,
  listPrizes,
  createPrize,
  updatePrize,
  listPurchases,
  updateFulfillment,
  listEventSurveys,
  getEventSurvey,
  listEntrants,
  getEventAggregate,
  getEventResponse,
};
