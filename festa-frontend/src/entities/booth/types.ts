// Booth facade(외부 표현) 계약의 FE측 타입 사본 — contracts/layout-api.md §6·§7이 정본
// 출처: specs/005-booth-studio-layout/contracts/layout-api.md §6·§7, data-model.md BoothFacade(R-11)

// 팔레트 endpoint(GET /booth-facade-palette)가 아직 없어(#17 진행 중) 4값을 상수로 둔다.
// 출처: docs/08_Backend_API_명세서.md:314, #17 합의 — 팔레트 확정 시 서버 조회로 교체
export const THEME_CODES = ['DEFAULT', 'SSAFY_BLUE', 'WARM', 'MONO'] as const;
export type ThemeCode = (typeof THEME_CODES)[number];

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
