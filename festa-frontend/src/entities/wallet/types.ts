// Wallet(코인 잔액·거래 내역) 계약의 FE측 타입 사본 — specs/003-wallet-coin/contracts/wallet-api.md가 정본
// 출처: origin/develop:specs/003-wallet-coin/contracts/wallet-api.md (docs/08 §8은 낡음 — Page 방식 확정)

// GET /api/v1/wallets/me 200 응답. 인증된 MEMBER 요청이면 서버 인터셉터가 당일 일일 지급(50코인)을
// 응답 전에 반영한다 — FE에 "지급 받기" 버튼이 없는 이유.
export interface Wallet {
  userId: number;
  balance: number;
  updatedAt: string; // ISO-8601 UTC
}

// 거래 내역 원소. reasonType은 유니온으로 좁히지 않는다 — 004(LEASE_PAYMENT)·010·012·014가 값을
// 계속 추가하며, 계약이 "모르는 값을 만나도 깨지지 말고 숨기지도 말 것"을 요구한다(SC-005).
export interface WalletTransaction {
  id: number;
  entryType: 'CHARGE' | 'SPEND' | 'REWARD' | 'REFUND';
  amount: number; // 부호 있음 — 지급 +, 차감 −
  balanceAfter: number;
  reasonType: string;
  // 서버 record에 @JsonInclude가 없어 값 없음이 키 생략이 아니라 null로 온다(오류 봉투의 NON_NULL과 다름)
  referenceType: string | null;
  referenceId: string | null; // 숫자를 담아도 string (예: leaseId "317")
  createdAt: string; // ISO-8601 UTC
}

// GET /api/v1/wallets/me/transactions 응답 봉투 — offset page 방식(cursor 아님),
// 정렬은 서버 고정(created_at DESC, id DESC)
export interface TransactionPage {
  content: WalletTransaction[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// 알려진 reasonType의 한글 라벨 — 003 시점 3종 + 004의 LEASE_PAYMENT
const REASON_LABELS: Record<string, string> = {
  INITIAL_GRANT: '가입 지급',
  DAILY_GRANT: '일일 지급',
  ADMIN_ADJUSTMENT: '운영자 조정',
  LEASE_PAYMENT: '부스 임대',
};

// 모르는 reasonType은 원문 코드 그대로 반환 — 항목을 숨기면 SC-005 위반
export function labelForReason(reasonType: string): string {
  return REASON_LABELS[reasonType] ?? reasonType;
}
