// Booth facade(외부 표현) 계약의 FE측 타입 사본 — contracts/layout-api.md §6·§7이 정본
// 출처: specs/005-booth-studio-layout/contracts/layout-api.md §6·§7, data-model.md BoothFacade(R-11)

// 팔레트 endpoint(GET /booth-facade-palette)가 아직 없어(#17 진행 중) 4값을 상수로 둔다.
// 출처: docs/08_Backend_API_명세서.md:314, #17 합의 — 팔레트 확정 시 서버 조회로 교체
export const THEME_CODES = ['DEFAULT', 'SSAFY_BLUE', 'WARM', 'MONO'] as const;
export type ThemeCode = (typeof THEME_CODES)[number];

// primaryColor 12색 팔레트 — 테마와 무관한 전역 1개(A안), themeCode 4종과 곱집합이 아니다.
// 값의 정본은 contracts/layout-api.md §6 표(2026-08-23 확정, #17) — 이 배열은 그 전사다.
// 팔레트 밖 hex는 BE가 400으로 거부한다(PR #71 구현) — FE 스와치가 이 목록만 내면 400 경로가 닫힌다.
export const FACADE_PALETTE = [
  { code: 'RED', hex: '#EF4444', label: '레드' },
  { code: 'ORANGE', hex: '#F97316', label: '오렌지' },
  { code: 'AMBER', hex: '#F59E0B', label: '앰버' },
  { code: 'YELLOW', hex: '#EAB308', label: '옐로' },
  { code: 'LIME', hex: '#84CC16', label: '라임' },
  { code: 'GREEN', hex: '#22C55E', label: '그린' },
  { code: 'TEAL', hex: '#14B8A6', label: '틸' },
  { code: 'CYAN', hex: '#06B6D4', label: '시안' },
  { code: 'BLUE', hex: '#3B82F6', label: '블루' },
  { code: 'INDIGO', hex: '#6366F1', label: '인디고' },
  { code: 'PURPLE', hex: '#A855F7', label: '퍼플' },
  { code: 'PINK', hex: '#EC4899', label: '핑크' },
] as const;

// 하이드레이션·mock 검증 공용 — 대소문자 무시 소속 판정(BE는 대문자 정규화 후 비교, PR #71)
export function isPaletteColor(hex: string): boolean {
  const upper = hex.toUpperCase();
  return FACADE_PALETTE.some((c) => c.hex === upper);
}

// Layout과 별개 엔티티다 — Draft/Publish도 낙관적 잠금도 없이 PUT 한 번으로 즉시 반영된다(R-11)
export interface BoothFacade {
  themeCode: ThemeCode;
  primaryColor: string | null; // #RRGGBB 6자리만, themeCode와 독립(#17)
  signText: string | null; // 최대 60자
  logoUrl: string | null; // https:// 최대 2048자, 업로드 아닌 URL 참조
}

// PUT /booths/{boothId}/facade 요청 본문 — 4필드 전부 nullable(§6)
export type FacadePutRequest = BoothFacade;

// GET /booths/{boothId} 응답에서 이 화면이 쓰는 부분만 — 004 소유 응답의 부분 사본(§7)
export interface BoothDetail {
  boothId: number;
  name: string;
  leaseStatus: string; // 'ACTIVE' 외 값이면 편집 진입 자체를 막는다(T021 이중 방어 ①)
  facade: BoothFacade | null;
}
