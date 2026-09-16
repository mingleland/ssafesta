// 콘솔 좌측 내비게이션의 정본. 라우트 세그먼트·라벨·설명을 한 곳에 둔다.
export const ADMIN_SECTIONS = [
  { id: 'overview', label: '운영 현황', hint: '오늘 처리할 것' },
  { id: 'members', label: '회원 관리', hint: '검색 · 정지 · 이력' },
  { id: 'admins', label: '관리자 관리', hint: '승격 · 강등' },
  { id: 'wallets', label: '코인 · 지갑', hint: '잔액 · 내역 · 조정' },
  { id: 'shop', label: '이벤트 상점', hint: '경품 · 구매 · 지급' },
  { id: 'surveys', label: '이벤트 설문', hint: '참여 · 응답 · 집계' },
  { id: 'booths', label: '부스 관리', hint: '조회 · 강제 비공개' },
] as const;

export type AdminSectionId = (typeof ADMIN_SECTIONS)[number]['id'];

export function isAdminSection(value: string | undefined): value is AdminSectionId {
  return ADMIN_SECTIONS.some((s) => s.id === value);
}

