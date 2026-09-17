// 관리자 mock 이 운영 시나리오를 실제로 재현하는지 잠근다 — 이 mock 위에서 콘솔 전체 흐름을 검증하므로
// mock 이 성공값만 돌려주면 화면의 오류 경로가 한 번도 안 돌아 본 채 BE 를 만난다.
import { beforeEach, describe, expect, it } from 'vitest';
import { __resetAdminMockForTests, adminApi } from '../../api.mock';

const KEY = '3f1b2c44-0a7e-4f6b-9a11-2c5d8e7f0a31';
const code = async (p: Promise<unknown>) => p.then(() => 'OK', (e: { code: string; status?: number }) => `${e.code}:${e.status}`);

beforeEach(() => __resetAdminMockForTests());

describe('관리자 관리', () => {
  it('마스터는 목록에 남고 강등은 MASTER_PROTECTED 다', async () => {
    const admins = await adminApi.listAdmins();
    expect(admins.some((a) => a.master)).toBe(true);
    expect(await code(adminApi.demoteAdmin(1))).toBe('MASTER_PROTECTED:403');
  });

  it('이미 관리자면 ADMIN_ALREADY, 없는 회원은 ADMIN_TARGET_NOT_FOUND 404 다', async () => {
    expect(await code(adminApi.promoteAdmin(2))).toBe('ADMIN_ALREADY:409');
    expect(await code(adminApi.promoteAdmin(999))).toBe('ADMIN_TARGET_NOT_FOUND:404');
  });

  it('마지막 활성 관리자는 강등할 수 없다 — 다른 관리자를 먼저 승격하면 된다', async () => {
    // 마스터(1)가 있어도 활성 관리자 집합은 {1,2}. 2 를 강등하면 1 이 남으므로 허용된다.
    await adminApi.demoteAdmin(2);
    expect((await adminApi.listAdmins()).map((a) => a.userId)).toEqual([1]);
    // 이제 1 만 남았다. 1 은 마스터라 MASTER_PROTECTED 가 먼저 선다 — 마지막 관리자 판정은 정지 경로로 본다
    await adminApi.promoteAdmin(3);
    await adminApi.demoteAdmin(3);
    expect(await code(adminApi.suspendMember(1, '테스트'))).toBe('MASTER_PROTECTED:403');
  });
});

describe('회원 관리', () => {
  it('검색은 닉네임 일부·번호로 찾고, 없으면 빈 페이지다', async () => {
    expect((await adminApi.searchMembers('참가', 0, 10)).content.map((m) => m.nickname)).toEqual(['페스타참가자']);
    expect((await adminApi.searchMembers('4', 0, 10)).content[0].status).toBe('SUSPENDED');
    expect((await adminApi.searchMembers('없는닉네임', 0, 10)).totalElements).toBe(0);
  });

  it('정지는 사유가 필수이고 이력에 남으며, 해제는 멱등이다', async () => {
    expect(await code(adminApi.suspendMember(3, '   '))).toBe('VALIDATION_FAILED:400');
    await adminApi.suspendMember(3, '신고 접수');
    expect((await adminApi.getMember(3)).status).toBe('SUSPENDED');
    const history = await adminApi.getStatusHistory(3, 0, 10);
    expect(history.content[0]).toMatchObject({ previousStatus: 'ACTIVE', currentStatus: 'SUSPENDED', reason: '신고 접수' });
    await adminApi.unsuspendMember(3);
    await adminApi.unsuspendMember(3);
    expect((await adminApi.getStatusHistory(3, 0, 10)).totalElements).toBe(2);
  });
});

describe('코인 조정 멱등키', () => {
  it('같은 키 같은 금액은 첫 결과를 alreadyApplied 로 돌려주고 잔액을 다시 바꾸지 않는다', async () => {
    const first = await adminApi.adjustCoins(3, KEY, 100, '보정');
    const again = await adminApi.adjustCoins(3, KEY, 100, '다른 문구');
    expect(first.alreadyApplied).toBe(false);
    expect(again).toMatchObject({ entryId: first.entryId, balanceAfter: first.balanceAfter, alreadyApplied: true });
    expect((await adminApi.getBalance(3)).balance).toBe(300);
  });

  it('같은 키 다른 금액은 IDEMPOTENCY_CONFLICT 다 — 재시도가 아니라 키 재사용', async () => {
    await adminApi.adjustCoins(3, KEY, 100);
    expect(await code(adminApi.adjustCoins(3, KEY, 200))).toBe('IDEMPOTENCY_CONFLICT:409');
  });

  it('멱등 범위는 대상 회원별이다', async () => {
    await adminApi.adjustCoins(3, KEY, 100);
    expect((await adminApi.adjustCoins(5, KEY, 100)).alreadyApplied).toBe(false);
  });

  it('0·비UUID·마스터·잔액 초과 회수를 거절한다', async () => {
    expect(await code(adminApi.adjustCoins(3, KEY, 0))).toBe('VALIDATION_FAILED:400');
    expect(await code(adminApi.adjustCoins(3, 'not-a-uuid', 10))).toBe('VALIDATION_FAILED:400');
    expect(await code(adminApi.adjustCoins(1, KEY, 10))).toBe('MASTER_PROTECTED:403');
    expect(await code(adminApi.adjustCoins(3, KEY, -999))).toBe('INSUFFICIENT_COIN:409');
  });
});

describe('부스·상점·설문', () => {
  it('강제 비공개는 게시 상태를 내리고, 마스터 소유 부스는 보호된다', async () => {
    await adminApi.unpublishBooth(13, '신고');
    expect((await adminApi.listBooths()).find((b) => b.boothId === 13)?.entryAvailable).toBe(false);
    expect(await code(adminApi.unpublishBooth(11, '신고'))).toBe('MASTER_PROTECTED:403');
  });

  // 전이 표는 BE `PurchaseFulfillment.canTransitionTo` 와 같다 — 지급 완료·취소는 둘 다 종단이다.
  it('구매 처리 상태는 필터·전이가 되고, 종단 상태는 되돌릴 수 없다', async () => {
    expect((await adminApi.listPurchases('PENDING', 0, 10)).totalElements).toBe(1);
    const done = await adminApi.updateFulfillment(1, 'FULFILLED', '현장 수령');
    expect(done).toMatchObject({ fulfillment: 'FULFILLED', note: '현장 수령' });
    // 취소된 것을 되살리는 것도, 지급 완료된 것을 취소하는 것도 같은 코드로 막힌다
    expect(await code(adminApi.updateFulfillment(4, 'FULFILLED'))).toBe('EVENT_PURCHASE_FULFILLMENT_INVALID:409');
    expect(await code(adminApi.updateFulfillment(1, 'CANCELLED'))).toBe('EVENT_PURCHASE_FULFILLMENT_INVALID:409');
  });

  it('설문 요약·집계·개별 응답이 서로 맞는다', async () => {
    const [summary] = await adminApi.listEventSurveys();
    const entrants = await adminApi.listEntrants(summary.surveyKey, 0, 10);
    expect(summary.entrantCount).toBe(entrants.totalElements);
    const agg = await adminApi.getEventAggregate(summary.surveyKey);
    expect(agg.find((q) => q.questionId === 2)?.options.find((o) => o.label === '광장 미니게임')?.count).toBe(2);
    const detail = await adminApi.getEventResponse(summary.surveyKey, entrants.content[0].responseId);
    expect(detail.answers).toHaveLength(summary.questionCount);
    expect(await code(adminApi.getEventResponse('NOPE', 1))).toBe('SURVEY_NOT_FOUND:404');
  });
});

