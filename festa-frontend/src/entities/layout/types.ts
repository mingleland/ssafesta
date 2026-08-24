// Booth Layout 계약의 FE측 타입 사본 — contracts/layout-api.md가 정본, 여기는 정확한 전사여야 한다
// (계약에 없는 필드를 보내면 서버가 409 LAYOUT_VALIDATION_FAILED/MALFORMED_LAYOUT으로 저장 자체를 거부한다)
// 출처: specs/005-booth-studio-layout/contracts/layout-api.md §1·§2·§3·§9, data-model.md

export type ObjectType =
  | 'AI_AGENT'
  | 'VIDEO_SCREEN'
  | 'PROJECT_PANEL'
  | 'SURVEY_KIOSK'
  | 'RECRUITMENT_BOARD'
  | 'CONSULTATION_DESK'
  | 'LAPTOP'
  | 'LIKE_VOTE'
  | 'FURNITURE'
  | 'DECORATION';

export interface Position {
  x: number;
  y: number; // 편집기는 항상 0으로 고정 기록 (2D 톱뷰, y 미노출)
  z: number;
}

export interface LayoutObject {
  objectId: string; // 1~64자 [A-Za-z0-9_-], 배치 안 유일. FE는 crypto.randomUUID() 생성 (R-02)
  type: ObjectType;
  position: Position;
  rotationY: number; // degree, [0,360). 0 = +Z(부스 정면)
  configId?: number; // 기능형 연결 콘텐츠 ID. 서버는 없으면 필드 자체를 생략(null 아님)
  assetCode?: string; // 장식형(FURNITURE·DECORATION) 외형 선택 코드
}

// Publish 검증 결과 원소 — errors[].objectId·field는 서버가 @JsonInclude(NON_NULL)이라
// 값이 없으면 키 자체가 빠진다(계약 문서 예시의 "objectId": null과 실제 구현이 다름 — #36 후속 요청).
// field는 Bean Validation 경로(rule: FIELD_INVALID)의 요청 필드 경로 — docs/08 §1.3-1(#58 C안, PR #71)
export interface ValidationDetail {
  rule: string;
  objectId?: string;
  field?: string;
  message: string; // 한글, 그대로 노출 가능
}

// GET /booths/{boothId}/layouts/draft 200 응답. 204(작업본 없음)는 이 함수가 null로 정규화한다(entities/layout/api.ts)
export interface DraftGetResponse {
  boothId: number;
  revision: number;
  schemaVersion: number;
  template: string; // 현재 PROJECT_EXHIBITION 단독 (#19 ④ — DEFAULT 제거, V11 이관)
  objects: LayoutObject[];
  updatedAt: string;
  updatedByUserId: number;
  publishedVersion: number | null; // 현재 공개 회차. "공개본과 다름" 표시에 쓴다
}

// PUT /booths/{boothId}/layouts/draft 요청 본문
export interface DraftPutRequest {
  expectedRevision: number; // 필수. 최초 저장은 0. 서버 revision과 다르면 409 LAYOUT_REVISION_CONFLICT
  schemaVersion: number;
  template: string;
  objects: LayoutObject[];
}

// PUT 200 응답 — GET과 필드가 다르다: updatedByUserId·publishedVersion 없음, warnings 있음(항상 배열)
export interface DraftPutResponse {
  boothId: number;
  revision: number;
  schemaVersion: number;
  template: string;
  objects: LayoutObject[];
  updatedAt: string;
  warnings: ValidationDetail[];
}

// POST /booths/{boothId}/layouts/publish 200 응답
export interface PublishResponse {
  boothId: number;
  publishedVersion: number;
  publishedAt: string;
  warnings: ValidationDetail[];
}

// GET /booths/{boothId}/layouts/published 200 응답 (Unity가 쓰는 것과 동일 스키마)
export interface PublishedLayout {
  boothId: number;
  version: number; // 공개 회차. schemaVersion과 혼동 금지
  schemaVersion: number;
  template: string;
  objects: LayoutObject[];
}

// GET /api/v1/booth-layout-templates 200 응답 (§9, bearerAuth 필요)
export interface TemplateCatalog {
  templates: Array<{
    template: string;
    footprint: { width: number; depth: number; height: number };
    maxObjects: number;
  }>;
}
