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

// 알려진 reasonType의 한글 라벨 — BE CoinReason(s) 의 **전체 사본**이다.
//
// SURVEY_REWARD·PURCHASE 는 2026-09-08 회귀(S15P21A604-538)에서 빠진 것이 드러나 더했고,
// DAILY_MISSION(#233)·SLOT_BET/SLOT_PAYOUT(#205)·PRIZE_PURCHASE(#217) 는 각 기능이
// 원장에 도달하면서 화면에 코드가 그대로 노출돼 더했다(2026-09-18).
//
// 원래는 "BE 열거 전체의 사본을 만들지 마라"가 방침이었지만, 폴백(원문 노출)이 SC-005 위반이
// 아니라 사용자 노출 결함으로 보이는 상태라 전수 매핑이 요구됐다. 사본이 낡는 drift 는
// CoinReason 으로 검색해 새 reason 이 추가되면 이 표와 함께 고치는 수동 규칙으로 감수한다 —
// 자동 대조는 BE 가 목록을 내려주는 계약 변경(별도 후속)에서 한다.
const REASON_LABELS: Record<string, string> = {
  INITIAL_GRANT: '가입 지급',
  DAILY_GRANT: '일일 지급',
  DAILY_MISSION: '일일 미션 보상',
  ADMIN_ADJUSTMENT: '운영자 조정',
  LEASE_PAYMENT: '부스 임대',
  SURVEY_REWARD: '설문 보상',
  PURCHASE: '아이템 구매',
  MINIGAME_REWARD: '미니게임 보상',
  SLOT_BET: '슬롯 베팅',
  SLOT_PAYOUT: '슬롯 당첨',
  PRIZE_PURCHASE: '경품 구매',
};

// 모르는 reasonType은 원문 코드 그대로 반환 — 항목을 숨기면 SC-005 위반
export function labelForReason(reasonType: string): string {
  return REASON_LABELS[reasonType] ?? reasonType;
}
