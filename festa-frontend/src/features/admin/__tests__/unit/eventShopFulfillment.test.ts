// 이벤트 상점 전이 규칙 (S15P21A604-853, GitLab #217 4번).
//
// 이 표는 BE `PurchaseFulfillment.canTransitionTo` 와 같아야 한다. FE 가 열어 둔 전이를 BE 가
// 409 로 막으면 운영자에게는 "눌렀는데 실패했다" 로만 보인다 — 애초에 고를 수 없어야 한다.
import { describe, expect, it } from 'vitest';
import { nextFulfillmentOptions } from '../../../../entities/admin/types';
import { describeAdminError } from '../../model/adminErrors';

describe('지급 처리 상태 전이', () => {
  it('PURCHASED 는 어디로든 간다', () => {
    expect(nextFulfillmentOptions('PURCHASED')).toEqual(['PENDING', 'FULFILLED', 'CANCELLED']);
  });

  it('PENDING 은 끝내거나 무효로만 간다 — 되돌아가지 않는다', () => {
    expect(nextFulfillmentOptions('PENDING')).toEqual(['FULFILLED', 'CANCELLED']);
  });

  it('FULFILLED 는 종단이다 — 물건이 나간 뒤에는 되감지 않는다 (BE 가 409 로 막는다)', () => {
    expect(nextFulfillmentOptions('FULFILLED')).toEqual([]);
  });

  it('CANCELLED 도 종단이다', () => {
    expect(nextFulfillmentOptions('CANCELLED')).toEqual([]);
  });
});

describe('전이 거절 문구', () => {
  it('409 EVENT_PURCHASE_FULFILLMENT_INVALID 를 운영자 문장으로 옮긴다 — 새로 고치라고 말해 준다', () => {
    const view = describeAdminError({
      code: 'EVENT_PURCHASE_FULFILLMENT_INVALID',
      message: '허용되지 않는 처리 상태 전이입니다.',
      status: 409,
      errors: [],
      warnings: [],
    });

    expect(view.title).toBe('이 상태로는 바꿀 수 없습니다');
    expect(view.message).toContain('새로 고친');
    // 서버가 정확히 거절한 것이라 재시도 버튼을 주지 않는다
    expect(view.retryable).toBe(false);
  });

  it('경품 404 도 코드를 그대로 내지 않는다', () => {
    const view = describeAdminError({
      code: 'EVENT_PRIZE_NOT_FOUND',
      message: '경품을 찾을 수 없습니다.',
      status: 404,
      errors: [],
      warnings: [],
    });

    expect(view.title).toBe('경품을 찾을 수 없습니다');
    expect(view.code).toBe('EVENT_PRIZE_NOT_FOUND');
  });
});

