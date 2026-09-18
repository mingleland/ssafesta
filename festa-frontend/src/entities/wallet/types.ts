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
//
// SURVEY_REWARD·PURCHASE 는 2026-09-08 회귀(S15P21A604-538)에서 빠진 것이 드러나 더했다.
// 설문 보상은 -528 이 Survey 실 어댑터를 붙이면서 **처음 도달 가능해진 경로**라 그때 보였다 —
// 화면에 `SURVEY_REWARD` 가 그대로 노출됐다(S15P21A604-543).
//
// 이 목록을 BE CoinReason 전체의 사본으로 만들지 않는다. 같은 열거가 두 곳에 생기면
// 그 사본이 낡는 것을 아무도 못 잡고, 지금 고치는 것과 같은 종류의 문제가 하나 더 생긴다.
// reason drift 자체를 막는 방법(BE 가 목록을 내려주거나 계약에 열거하고 그것을 근거로 삼는 것)은
// 별도 후속이다.
const REASON_LABELS: Record<string, string> = {
  INITIAL_GRANT: '가입 지급',
  DAILY_GRANT: '일일 지급',
  ADMIN_ADJUSTMENT: '운영자 조정',
  LEASE_PAYMENT: '부스 임대',
  SURVEY_REWARD: '설문 보상',
  PURCHASE: '아이템 구매',
  MINIGAME_REWARD: '미니게임 보상',
  DAILY_MISSION: '일일 미션 보상',
};

// 모르는 reasonType은 원문 코드 그대로 반환 — 항목을 숨기면 SC-005 위반
export function labelForReason(reasonType: string): string {
  return REASON_LABELS[reasonType] ?? reasonType;
}
