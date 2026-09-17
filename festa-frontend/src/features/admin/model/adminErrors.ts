// 관리자 콘솔 오류를 운영자 문장으로 옮긴다 (S15P21A604-828).
//
// raw code 를 화면에 그대로 내지 않는다. 특히 MASTER_PROTECTED·ADMIN_ALREADY·ADMIN_LAST_ONE 은 "실패" 가
// 아니라 **되돌릴 수 없는 상태를 막아 준 것**이라, 그 뜻을 말해 줘야 운영자가 왜 막혔는지 안다.
// 진단은 따로 남긴다 — code·status·requestId 가 콘솔에 찍혀야 서버 로그와 맞붙는다.
import { isApiError } from '../../../shared/api/client';

export interface AdminErrorView {
  code: string;
  title: string;
  message: string;
  /** 다시 시도하면 결과가 달라질 수 있는가. 서버가 정확히 거절한 것(4xx)은 false 다 */
  retryable: boolean;
}

const KNOWN: Record<string, { title: string; message: string }> = {
  MASTER_PROTECTED: { title: '보호된 계정입니다', message: '마스터 계정과 그 소유 자원은 강등·정지·코인 조정·비공개 대상이 될 수 없습니다.' },
  ADMIN_ALREADY: { title: '이미 관리자입니다', message: '이 회원은 이미 관리자 권한을 갖고 있어 다시 승격할 필요가 없습니다.' },
  ADMIN_LAST_ONE: { title: '마지막 관리자는 해제할 수 없습니다', message: '관리자가 0명이 되면 API 로는 되돌릴 수 없습니다. 먼저 다른 관리자를 승격한 뒤 다시 시도하세요.' },
  IDEMPOTENCY_CONFLICT: { title: '이 조정 요청은 이미 다른 내용으로 처리되었습니다', message: '같은 요청 키에 다른 금액이 이미 반영돼 있습니다. 재시도하지 말고 새로운 조정을 시작해 주세요.' },
  ADMIN_TARGET_NOT_FOUND: { title: '대상 회원을 찾을 수 없습니다', message: '회원 번호를 다시 확인해 주세요.' },
  USER_NOT_FOUND: { title: '대상 회원을 찾을 수 없습니다', message: '회원 번호를 다시 확인해 주세요.' },
  FORBIDDEN: { title: '관리자 권한이 없습니다', message: '이 계정은 관리자가 아니거나 권한이 회수되었습니다. 권한은 매 요청 서버가 판정합니다.' },
  MEMBER_ONLY: { title: '회원 계정만 이용할 수 있습니다', message: '게스트 세션으로는 관리자 기능을 쓸 수 없습니다.' },
  INSUFFICIENT_COIN: { title: '잔액보다 많이 회수할 수 없습니다', message: '회수 금액이 현재 잔액을 넘습니다.' },
  COIN_BALANCE_OVERFLOW: { title: '지급 후 잔액이 표현 범위를 넘습니다', message: '금액을 줄여 주세요.' },
  WALLET_NOT_FOUND: { title: '지갑이 없는 회원입니다', message: '회원인데 지갑 행이 없습니다. BE 확인이 필요합니다.' },
  BOOTH_NOT_FOUND: { title: '부스를 찾을 수 없습니다', message: '이미 반납됐거나 번호가 틀렸을 수 있습니다.' },
  SURVEY_NOT_FOUND: { title: '이벤트 설문을 찾을 수 없습니다', message: '설문 key 를 확인해 주세요.' },
  VALIDATION_FAILED: { title: '입력값이 올바르지 않습니다', message: '' },
};

export function describeAdminError(error: unknown): AdminErrorView {
  if (isApiError(error)) {
    console.warn(`[admin] 요청 실패 — ${error.code}${error.status === undefined ? '' : ` (${error.status})`}` +
      (error.requestId === undefined ? '' : ` requestId=${error.requestId}`) + `: ${error.message}`);
    const known = KNOWN[error.code];
    const status = error.status ?? 0;
    return {
      code: error.code,
      title: known?.title ?? '요청을 처리하지 못했습니다',
      message: known?.message ? known.message : error.message,
      retryable: !(status >= 400 && status < 500 && status !== 408 && status !== 429),
    };
  }
  const message = error instanceof Error ? error.message : String(error);
  console.warn('[admin] 요청 실패 —', message);
  return { code: 'UNKNOWN', title: '서버에 연결하지 못했습니다', message, retryable: true };
}

