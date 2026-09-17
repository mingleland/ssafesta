// 관리자 콘솔 mock — real 과 같은 AdminRepository 모양이고 BE 오류 봉투와 같은 형태로 던진다.
//
// 성공값 한 건이 아니라 **운영 시나리오**를 들고 있다. BE 도착 전에 콘솔의 전체 흐름을 화면에서
// 재현하려면 실패 경로가 있어야 한다: 마스터 보호·중복 승격·마지막 관리자 강등·같은 멱등키 재시도·
// 멱등키 충돌·없는 회원(404)·정지 회원·검색 결과 없음. 오류 코드와 status 는 BE ErrorCode 와 같다.
//
// 마스터·관리자 판정: userId 1 이 마스터, 2 가 관리자, 나머지는 회원. 콘솔을 여는 나(mock 세션)는 2 다.
import type { ApiError } from '../../shared/api/client';
import type {
  AccountStatusHistoryView,
  AdjustmentResult,
  AdminBoothView,
  AdminCapability,
  AdminLedgerEntry,
  AdminMemberView,
  AdminRepository,
  AdminView,
  EventEntrantView,
  EventQuestionAggregate,
  EventResponseDetail,
  Page,
  PrizePurchaseView,
  PrizeView,
} from './types';

function apiError(code: string, status: number, message: string): ApiError {
  return { code, message, status, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

function paginate<T>(items: T[], page: number, size: number): Page<T> {
  if (page < 0 || size < 1 || size > 100) throw apiError('VALIDATION_FAILED', 400, '요청 값이 올바르지 않습니다.');
  const start = page * size;
  return { content: items.slice(start, start + size), page, size, totalElements: items.length, totalPages: Math.ceil(items.length / size) };
}

const at = (h: number, m = 0) => new Date(Date.UTC(2026, 8, 16, h, m)).toISOString();

interface MockUser extends AdminMemberView {
  balance: number;
}

const SELF_USER_ID = 2;
let capability: AdminCapability = { admin: true, master: false };

function seedUsers(): MockUser[] {
  return [
    { userId: 1, nickname: '구글 황덕', status: 'ACTIVE', admin: true, master: true, providers: ['GOOGLE'], joinedAt: at(1), balance: 1200 },
    { userId: 2, nickname: '이정헌', status: 'ACTIVE', admin: true, master: false, providers: ['GOOGLE'], joinedAt: at(1, 5), balance: 350 },
    { userId: 3, nickname: '페스타참가자', status: 'ACTIVE', admin: false, master: false, providers: ['KAKAO'], joinedAt: at(2), balance: 200 },
    { userId: 4, nickname: '정지된회원', status: 'SUSPENDED', admin: false, master: false, providers: ['GOOGLE'], joinedAt: at(2, 30), balance: 40 },
    { userId: 5, nickname: '싸피생', status: 'ACTIVE', admin: false, master: false, providers: ['SSAFY'], joinedAt: at(3), balance: 505 },
    { userId: 6, nickname: '부스주인', status: 'ACTIVE', admin: false, master: false, providers: ['GOOGLE'], joinedAt: at(3, 10), balance: 90 },
    { userId: 7, nickname: '이벤트참여자', status: 'ACTIVE', admin: false, master: false, providers: ['KAKAO'], joinedAt: at(4), balance: 260 },
  ];
}

let users = seedUsers();
let history: AccountStatusHistoryView[] = [
  { userId: 4, previousStatus: 'ACTIVE', currentStatus: 'SUSPENDED', reason: '부스 내 욕설 신고 3건', actorUserId: 1, createdAt: at(5) },
];
let ledger: (AdminLedgerEntry & { userId: number })[] = [
  { userId: 3, id: 101, entryType: 'REWARD', amount: 200, balanceAfter: 200, reasonType: 'INITIAL_GRANT', referenceType: null, referenceId: null, createdAt: at(2) },
  { userId: 5, id: 102, entryType: 'REWARD', amount: 200, balanceAfter: 200, reasonType: 'INITIAL_GRANT', referenceType: null, referenceId: null, createdAt: at(3) },
  { userId: 5, id: 103, entryType: 'REWARD', amount: 50, balanceAfter: 250, reasonType: 'DAILY_GRANT', referenceType: null, referenceId: null, createdAt: at(3, 1) },
  { userId: 5, id: 104, entryType: 'REWARD', amount: 300, balanceAfter: 550, reasonType: 'SURVEY_REWARD', referenceType: 'SURVEY', referenceId: '9', createdAt: at(6) },
  { userId: 5, id: 105, entryType: 'SPEND', amount: -45, balanceAfter: 505, reasonType: 'PURCHASE', referenceType: 'EVENT_PRIZE', referenceId: '2', createdAt: at(7) },
  { userId: 7, id: 106, entryType: 'REWARD', amount: 200, balanceAfter: 200, reasonType: 'INITIAL_GRANT', referenceType: null, referenceId: null, createdAt: at(4) },
  { userId: 7, id: 107, entryType: 'REWARD', amount: 300, balanceAfter: 500, reasonType: 'SURVEY_REWARD', referenceType: 'SURVEY', referenceId: '9', createdAt: at(6, 5) },
  { userId: 7, id: 108, entryType: 'SPEND', amount: -240, balanceAfter: 260, reasonType: 'PURCHASE', referenceType: 'EVENT_PRIZE', referenceId: '1', createdAt: at(8) },
];
let nextLedgerId = 500;
/** 멱등키 → 처리 결과. 키는 대상 회원별이다(BE 와 같다) */
let adjustments = new Map<string, { signedAmount: number; result: AdjustmentResult }>();

let booths: AdminBoothView[] = [
  { slotId: 1, slotCode: 'F11-R01', boothId: 11, boothName: 'AI 상담 부스', entryAvailable: true, leaseEndsAt: at(23) },
  { slotId: 3, slotCode: 'F11-R03', boothId: 13, boothName: '싸피 프로젝트관', entryAvailable: true, leaseEndsAt: at(23) },
  { slotId: 7, slotCode: 'F11-R07', boothId: 17, boothName: null, entryAvailable: false, leaseEndsAt: at(23) },
];
/** 마스터 소유 부스 — 강제 비공개가 MASTER_PROTECTED 로 거절된다 */
const MASTER_BOOTH_ID = 11;

let prizes: PrizeView[] = [
  { prizeId: 1, name: '싸피 후드집업', priceCoin: 240, stock: 3, active: true },
  { prizeId: 2, name: '스티커 세트', priceCoin: 45, stock: null, active: true },
  { prizeId: 3, name: '텀블러', priceCoin: 180, stock: 0, active: false },
];
let purchases: PrizePurchaseView[] = [
  { purchaseId: 1, prizeId: 1, prizeName: '싸피 후드집업', buyerUserId: 7, buyerNickname: '이벤트참여자', quantity: 1, coinSpent: 240, ledgerEntryId: 108, purchasedAt: at(8), fulfillment: 'PENDING', note: null, updatedAt: at(8) },
  { purchaseId: 2, prizeId: 2, prizeName: '스티커 세트', buyerUserId: 5, buyerNickname: '싸피생', quantity: 1, coinSpent: 45, ledgerEntryId: 105, purchasedAt: at(7), fulfillment: 'FULFILLED', note: '현장 수령', updatedAt: at(7, 40) },
  { purchaseId: 3, prizeId: 2, prizeName: '스티커 세트', buyerUserId: 3, buyerNickname: '페스타참가자', quantity: 2, coinSpent: 90, ledgerEntryId: null, purchasedAt: at(9), fulfillment: 'PURCHASED', note: null, updatedAt: at(9) },
  { purchaseId: 4, prizeId: 3, prizeName: '텀블러', buyerUserId: 6, buyerNickname: '부스주인', quantity: 1, coinSpent: 180, ledgerEntryId: 99, purchasedAt: at(6, 30), fulfillment: 'CANCELLED', note: '재고 소진으로 취소, 코인 환불 필요', updatedAt: at(6, 50) },
];

const SURVEY_KEY = 'SSAFESTA_2026';
const questions = [
  { questionId: 1, prompt: '이번 축제 전체 만족도는?', type: 'RATING', options: ['1', '2', '3', '4', '5'] },
  { questionId: 2, prompt: '가장 좋았던 공간은?', type: 'SINGLE_CHOICE', options: ['부스 전시', '광장 미니게임', 'AI 상담', '아바타 꾸미기'] },
  { questionId: 3, prompt: '내년에 바라는 점을 적어 주세요', type: 'TEXT', options: [] as string[] },
];
let responses: EventResponseDetail[] = [
  { responseId: 901, userId: 5, nickname: '싸피생', submittedAt: at(6), answers: [{ questionId: 1, prompt: questions[0].prompt, value: '5' }, { questionId: 2, prompt: questions[1].prompt, value: '광장 미니게임' }, { questionId: 3, prompt: questions[2].prompt, value: '미니게임 종류가 더 많았으면' }] },
  { responseId: 902, userId: 7, nickname: '이벤트참여자', submittedAt: at(6, 5), answers: [{ questionId: 1, prompt: questions[0].prompt, value: '4' }, { questionId: 2, prompt: questions[1].prompt, value: '부스 전시' }, { questionId: 3, prompt: questions[2].prompt, value: '' }] },
  { responseId: 903, userId: 3, nickname: '페스타참가자', submittedAt: at(9, 20), answers: [{ questionId: 1, prompt: questions[0].prompt, value: '4' }, { questionId: 2, prompt: questions[1].prompt, value: '광장 미니게임' }, { questionId: 3, prompt: questions[2].prompt, value: '로딩이 조금 길었어요' }] },
  { responseId: 904, userId: 6, nickname: '부스주인', submittedAt: at(10), answers: [{ questionId: 1, prompt: questions[0].prompt, value: '3' }, { questionId: 2, prompt: questions[1].prompt, value: 'AI 상담' }, { questionId: 3, prompt: questions[2].prompt, value: '부스 배치 범위를 넓혀 주세요' }] },
];
let surveyClosed = false;

function requireUser(userId: number): MockUser {
  const user = users.find((u) => u.userId === userId);
  if (user === undefined) throw apiError('ADMIN_TARGET_NOT_FOUND', 404, '대상 회원을 찾을 수 없습니다.');
  return user;
}

function requireNotMaster(userId: number): void {
  if (users.find((u) => u.userId === userId)?.master) throw apiError('MASTER_PROTECTED', 403, '보호된 계정입니다.');
}

function view(u: MockUser): AdminMemberView {
  const { balance: _balance, ...rest } = u;
  return rest;
}

export const adminApi: AdminRepository = {
  async getCapability() {
    return { ...capability };
  },

  async listAdmins(): Promise<AdminView[]> {
    return users.filter((u) => u.admin).map((u) => ({ userId: u.userId, nickname: u.nickname, master: u.master }));
  },

  async promoteAdmin(userId, note) {
    const user = requireUser(userId);
    if (user.status === 'SUSPENDED') throw apiError('FORBIDDEN', 403, '정지된 계정은 관리자로 승격할 수 없습니다.');
    if (user.admin) throw apiError('ADMIN_ALREADY', 409, '이미 관리자입니다.');
    user.admin = true;
    void note;
    return { userId: user.userId, nickname: user.nickname, master: user.master };
  },

  async demoteAdmin(userId) {
    const user = requireUser(userId);
    requireNotMaster(userId);
    if (!user.admin) return;
    const activeAdmins = users.filter((u) => u.admin && u.status === 'ACTIVE');
    if (activeAdmins.length <= 1) throw apiError('ADMIN_LAST_ONE', 409, '마지막 관리자는 해제할 수 없습니다.');
    user.admin = false;
  },

  async searchMembers(query, page, size) {
    const needle = query.trim().toLowerCase();
    const hit = users.filter((u) => needle === '' || u.nickname.toLowerCase().includes(needle) || String(u.userId) === needle);
    return paginate(hit.map(view), page, size);
  },

  async getMember(userId) {
    return view(requireUser(userId));
  },

  async suspendMember(userId, reason) {
    if (reason.trim() === '' || reason.length > 500) throw apiError('VALIDATION_FAILED', 400, '정지 사유는 1~500자여야 합니다.');
    const user = requireUser(userId);
    requireNotMaster(userId);
    if (user.status === 'SUSPENDED') return;
    if (user.admin && users.filter((u) => u.admin && u.status === 'ACTIVE').length <= 1) {
      throw apiError('ADMIN_LAST_ONE', 409, '마지막 관리자는 정지할 수 없습니다.');
    }
    user.status = 'SUSPENDED';
    history.unshift({ userId, previousStatus: 'ACTIVE', currentStatus: 'SUSPENDED', reason, actorUserId: SELF_USER_ID, createdAt: new Date().toISOString() });
  },

  async unsuspendMember(userId) {
    const user = requireUser(userId);
    requireNotMaster(userId);
    if (user.status === 'ACTIVE') return;
    user.status = 'ACTIVE';
    history.unshift({ userId, previousStatus: 'SUSPENDED', currentStatus: 'ACTIVE', reason: null, actorUserId: SELF_USER_ID, createdAt: new Date().toISOString() });
  },

  async getStatusHistory(userId, page, size) {
    requireUser(userId);
    return paginate(history.filter((h) => h.userId === userId), page, size);
  },

  async getBalance(userId) {
    const user = requireUser(userId);
    return { userId, balance: user.balance, updatedAt: new Date().toISOString() };
  },

  async getLedger(userId, page, size) {
    requireUser(userId);
    const rows = ledger.filter((e) => e.userId === userId).sort((a, b) => b.id - a.id);
    return paginate(rows.map(({ userId: _u, ...entry }) => entry), page, size);
  },

  async adjustCoins(userId, idempotencyKey, signedAmount, note) {
    if (!/^[0-9a-f-]{36}$/i.test(idempotencyKey)) throw apiError('VALIDATION_FAILED', 400, 'Idempotency-Key는 UUID여야 합니다.');
    if (signedAmount === 0) throw apiError('VALIDATION_FAILED', 400, '조정 금액은 0일 수 없습니다.');
    const user = requireUser(userId);
    requireNotMaster(userId);
    const scoped = `${userId}:${idempotencyKey.toLowerCase()}`;
    const seen = adjustments.get(scoped);
    if (seen !== undefined) {
      if (seen.signedAmount !== signedAmount) throw apiError('IDEMPOTENCY_CONFLICT', 409, '같은 멱등성 키로 다른 요청이 이미 처리됐습니다.');
      return { ...seen.result, alreadyApplied: true };
    }
    if (user.balance + signedAmount < 0) throw apiError('INSUFFICIENT_COIN', 409, '잔액보다 많이 회수할 수 없습니다.');
    user.balance += signedAmount;
    const entry: AdminLedgerEntry & { userId: number } = {
      userId,
      id: nextLedgerId++,
      entryType: signedAmount > 0 ? 'CHARGE' : 'SPEND',
      amount: signedAmount,
      balanceAfter: user.balance,
      reasonType: 'ADMIN_ADJUSTMENT',
      referenceType: 'ADMIN_USER',
      referenceId: String(SELF_USER_ID),
      createdAt: new Date().toISOString(),
    };
    ledger.push(entry);
    void note;
    const result: AdjustmentResult = { userId, entryId: entry.id, balanceAfter: user.balance, alreadyApplied: false };
    adjustments.set(scoped, { signedAmount, result });
    return result;
  },

  async listBooths() {
    return booths.map((b) => ({ ...b }));
  },

  async unpublishBooth(boothId, reason) {
    if (reason.trim() === '' || reason.length > 500) throw apiError('VALIDATION_FAILED', 400, '비공개 사유는 1~500자여야 합니다.');
    const booth = booths.find((b) => b.boothId === boothId);
    if (booth === undefined) throw apiError('BOOTH_NOT_FOUND', 404, '부스를 찾을 수 없습니다.');
    if (boothId === MASTER_BOOTH_ID) throw apiError('MASTER_PROTECTED', 403, '보호된 계정입니다.');
    booth.entryAvailable = false;
  },

  async listPrizes() {
    return prizes.map((p) => ({ ...p }));
  },

  async listPurchases(status, page, size) {
    const rows = purchases.filter((p) => status === 'ALL' || p.fulfillment === status).sort((a, b) => b.purchaseId - a.purchaseId);
    return paginate(rows.map((p) => ({ ...p })), page, size);
  },

  async updateFulfillment(purchaseId, status, note) {
    const purchase = purchases.find((p) => p.purchaseId === purchaseId);
    if (purchase === undefined) throw apiError('PURCHASE_NOT_FOUND', 404, '구매 내역을 찾을 수 없습니다.');
    if (purchase.fulfillment === 'CANCELLED' && status !== 'CANCELLED') {
      throw apiError('PURCHASE_ALREADY_CANCELLED', 409, '취소된 구매는 되돌릴 수 없습니다.');
    }
    purchase.fulfillment = status;
    purchase.note = note?.trim() ? note.trim() : purchase.note;
    purchase.updatedAt = new Date().toISOString();
    return { ...purchase };
  },

  async listEventSurveys() {
    return [await this.getEventSurvey(SURVEY_KEY)];
  },

  async getEventSurvey(surveyKey) {
    if (surveyKey !== SURVEY_KEY) throw apiError('SURVEY_NOT_FOUND', 404, '그런 이벤트 설문이 없습니다.');
    return { surveyKey, surveyId: 9, title: 'SSAFESTA 2026 축제 만족도 설문', closed: surveyClosed, rewardCoin: 300, questionCount: questions.length, entrantCount: responses.length };
  },

  async listEntrants(surveyKey, page, size) {
    if (surveyKey !== SURVEY_KEY) throw apiError('SURVEY_NOT_FOUND', 404, '그런 이벤트 설문이 없습니다.');
    const rows: EventEntrantView[] = [...responses]
      .sort((a, b) => b.submittedAt.localeCompare(a.submittedAt))
      .map((r) => ({ responseId: r.responseId, userId: r.userId, nickname: r.nickname, submittedAt: r.submittedAt }));
    return paginate(rows, page, size);
  },

  async getEventAggregate(surveyKey): Promise<EventQuestionAggregate[]> {
    if (surveyKey !== SURVEY_KEY) throw apiError('SURVEY_NOT_FOUND', 404, '그런 이벤트 설문이 없습니다.');
    return questions.map((qn) => {
      const values = responses.map((r) => r.answers.find((a) => a.questionId === qn.questionId)?.value ?? '').filter((v) => v !== '');
      return {
        questionId: qn.questionId,
        prompt: qn.prompt,
        type: qn.type,
        answered: values.length,
        options: qn.options.map((label) => ({ label, count: values.filter((v) => v === label).length })),
        textSamples: qn.type === 'TEXT' ? values.slice(0, 5) : [],
      };
    });
  },

  async getEventResponse(surveyKey, responseId) {
    if (surveyKey !== SURVEY_KEY) throw apiError('SURVEY_NOT_FOUND', 404, '그런 이벤트 설문이 없습니다.');
    const found = responses.find((r) => r.responseId === responseId);
    if (found === undefined) throw apiError('SURVEY_RESPONSE_NOT_FOUND', 404, '응답을 찾을 수 없습니다.');
    return { ...found, answers: found.answers.map((a) => ({ ...a })) };
  },
};

/** 테스트·시나리오 전용 — 콘솔을 여는 사람의 권한을 바꾼다 (비관리자 화면 재현) */
export function __setMockCapability(next: AdminCapability): void {
  capability = { ...next };
}

export function __resetAdminMockForTests(): void {
  capability = { admin: true, master: false };
  users = seedUsers();
  history = history.slice(0, 0).concat([{ userId: 4, previousStatus: 'ACTIVE', currentStatus: 'SUSPENDED', reason: '부스 내 욕설 신고 3건', actorUserId: 1, createdAt: at(5) }]);
  ledger = ledger.filter((e) => e.id < 500);
  nextLedgerId = 500;
  adjustments = new Map();
  booths = booths.map((b) => ({ ...b, entryAvailable: b.boothId !== 17 }));
  prizes = prizes.map((p) => ({ ...p }));
  purchases = purchases.map((p, i) => ({ ...p, fulfillment: (['PENDING', 'FULFILLED', 'PURCHASED', 'CANCELLED'] as const)[i], note: i === 1 ? '현장 수령' : i === 3 ? '재고 소진으로 취소, 코인 환불 필요' : null }));
  responses = responses.map((r) => ({ ...r }));
  surveyClosed = false;
}
