// 로컬 개발용 지갑 mock — 초기 잔액 200(가입 지급), 임대 차감은 leaseApi.mock이 debitForLease()를
// 호출해 이 모듈의 잔액과 정합을 유지한다(잔액의 단일 원천). VITE_USE_MOCK=true일 때 api.ts 대신 사용.
// 출처: specs/003-wallet-coin/contracts/wallet-api.md
import type { ApiError } from '../../shared/api/client';
import type { TransactionPage, Wallet, WalletTransaction } from './types';

const STORAGE_KEY = 'festa-mock-wallet';
const INITIAL_BALANCE = 200; // 가입 지급(C 확정 수치)

function loadBalance(): number {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw === null ? INITIAL_BALANCE : Number(raw);
  } catch {
    return INITIAL_BALANCE;
  }
}

function persistBalance(): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, String(balance));
  } catch {
    // sessionStorage 미가용 — 이번 세션만 메모리로 동작
  }
}

let balance = loadBalance();

function apiError(code: string, message: string): ApiError {
  return { code, message, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

export async function getWallet(): Promise<Wallet> {
  return { userId: 1, balance, updatedAt: new Date().toISOString() };
}

// leaseApi.mock 전용 — HTTP 노출이 아니라 mock 간 잔액 정합용 내부 훅.
// 실서버에서 차감은 임대 트랜잭션 안에서만 일어난다(계약 "만들지 않는 것").
// 부족 시 서버와 같은 봉투로 거부하되, 부족액을 message에 일부러 넣는다 —
// FE가 이 숫자를 파싱하지 않아야 한다는 계약(구조화 필드 없음)의 실측 감시용.
export function debitForLease(amount: number): number {
  if (balance < amount) {
    throw apiError('INSUFFICIENT_COIN', `코인이 부족합니다. 필요: ${amount}, 잔액: ${balance}`);
  }
  balance -= amount;
  persistBalance();
  return balance;
}

// 결정적 거래 시드 — 페이지네이션 경계 검증이 가능한 45건.
// id 내림차순(= created_at DESC, id DESC 서버 정렬 재현). 미지 reasonType 1건 포함 — 라벨 폴백 실측용.
function seedTransactions(): WalletTransaction[] {
  const list: WalletTransaction[] = [];
  for (let i = 45; i >= 1; i--) {
    const isSpend = i % 3 === 0;
    list.push({
      id: i,
      entryType: isSpend ? 'SPEND' : 'REWARD',
      amount: isSpend ? -100 : 50,
      balanceAfter: 200 + i, // 표시 검증용 결정값 — 실제 누계는 아님
      reasonType: i === 44 ? 'FUTURE_UNKNOWN_REASON' : isSpend ? 'LEASE_PAYMENT' : 'DAILY_GRANT',
      referenceType: isSpend ? 'BOOTH_LEASE' : undefined,
      referenceId: isSpend ? String(300 + i) : undefined,
      createdAt: new Date(Date.UTC(2026, 7, 24, 0, i)).toISOString(),
    });
  }
  return list;
}

const transactions = seedTransactions();

export async function getTransactions(page: number, size = 20): Promise<TransactionPage> {
  if (page < 0 || size < 1 || size > 100) {
    throw apiError('VALIDATION_FAILED', '요청 값이 올바르지 않습니다.');
  }
  const start = page * size;
  return {
    content: transactions.slice(start, start + size),
    page,
    size,
    totalElements: transactions.length,
    totalPages: Math.ceil(transactions.length / size),
  };
}

export function __resetWalletMockForTests(): void {
  balance = INITIAL_BALANCE;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // 무시
  }
}
