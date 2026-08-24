// Wallet 조회 real API — MEMBER 전용 2종. 차감·충전 endpoint는 계약상 영구 부재(차감은 기능
// 서버 로직에서만 발생 — 헌법 16조). 출처: specs/003-wallet-coin/contracts/wallet-api.md
import { api } from '../../shared/api/client';
import type { TransactionPage, Wallet } from './types';

export async function getWallet(): Promise<Wallet> {
  return api<Wallet>('/api/v1/wallets/me');
}

// page 0 기반, size 1~100(기본 20) — 범위 밖은 서버가 400 VALIDATION_FAILED
export async function getTransactions(page: number, size = 20): Promise<TransactionPage> {
  return api<TransactionPage>(`/api/v1/wallets/me/transactions?page=${page}&size=${size}`);
}
