// BE 오류코드 → 팔레트/상점 UI 어휘. 코드는 전부 backend ErrorCode.java 실물 — 발명 없음.
import { isApiError } from '../../shared/api/client';

export type PurchaseErrorKind =
  | 'insufficient_coin' // 409 — 잔액 부족
  | 'already_owned' // 409 — 중복 구매 (다른 기기·탭에서 이미 샀을 때)
  | 'not_on_sale' // 409
  | 'not_found' // 404
  | 'member_only' // 403 — 게스트 (헌법 12조, spec 012 FR-010)
  | 'network';

export function toPurchaseErrorKind(e: unknown): PurchaseErrorKind {
  if (isApiError(e)) {
    switch (e.code) {
      case 'INSUFFICIENT_COIN':
        return 'insufficient_coin';
      case 'ITEM_ALREADY_OWNED':
        return 'already_owned';
      case 'ITEM_NOT_ON_SALE':
        return 'not_on_sale';
      case 'CATALOG_ITEM_NOT_FOUND':
        return 'not_found';
      case 'MEMBER_ONLY':
      case 'UNAUTHORIZED':
        return 'member_only';
    }
  }
  return 'network';
}
