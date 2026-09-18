// 로컬 개발용 메모리 mock — facade PUT의 서버 검증(§6)과 만료 거부(§6 409)를 재현한다.
// VITE_USE_MOCK=true일 때 facadeApi.ts 대신 이 모듈을 쓴다(선택은 facadeApi.select.ts에서 한다).
// 출처: specs/005-booth-studio-layout/contracts/layout-api.md §6·§7

import type { ApiError } from '../../shared/api/client';
import { THEME_CODES, isPaletteColor } from './types';
import type { BoothDetail, BoothFacade, FacadePutRequest } from './types';
import { getMockHomepageUrl } from './homepageApi.mock';
import { getMockPublishedVersion } from './layoutApi.mock';

// 999 sentinel — 임대 만료 UX 수동 검증용 (옛 layout mock 과 같은 관용구).
// 실 BE에는 없는 값이라 real facadeApi.ts에는 이 분기가 없다.
const LEASE_EXPIRED_BOOTH_ID = 999;

const HTTPS_URL = /^https:\/\//;

function apiError(code: string, message: string): ApiError {
  return { code, message, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

// Bean Validation 실패의 실서버 봉투(docs/08 §1.3-1, #58 C안·PR #71) — rule은 규칙 어휘 고정,
// 문제 필드는 field에 담는다. 이전 mock이 errors: []로 내던 것은 서버보다 느슨한 결함이었다.
function fieldError(field: string, message: string): ApiError {
  return {
    code: 'VALIDATION_FAILED',
    message: '요청 값이 올바르지 않습니다.',
    requestId: `mock_${Date.now()}`,
    errors: [{ rule: 'FIELD_INVALID', field, message }],
    warnings: [],
  };
}

const STORAGE_KEY = 'festa-mock-booth-facades';

function loadFacades(): Map<number, BoothFacade> {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return new Map();
    return new Map(JSON.parse(raw) as Array<[number, BoothFacade]>);
  } catch {
    return new Map();
  }
}

function persistFacades(): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(Array.from(facades.entries())));
  } catch {
    // sessionStorage 미가용 — 이번 세션만 메모리로 동작
  }
}

const facades = loadFacades();

function defaultFacade(): BoothFacade {
  return { themeCode: 'DEFAULT', primaryColor: null, signText: null, logoUrl: null };
}

// 부스 이름 (S15P21A604-756). facade 와 저장소가 다르다 — 서버도 booths.name 컬럼이고
// 응답 facade 에는 실리지 않는다. 세션 유지는 하지 않는다: mock 의 목적은 계약 재현이다.
const names = new Map<number, string>();
const DEFAULT_BOOTH_NAME = 'AI 프로젝트 전시관';

export async function getBooth(boothId: number): Promise<BoothDetail> {
  return {
    boothId,
    name: names.get(boothId) ?? DEFAULT_BOOTH_NAME,
    leaseStatus: boothId === LEASE_EXPIRED_BOOTH_ID ? 'EXPIRED' : 'ACTIVE',
    facade: facades.get(boothId) ?? defaultFacade(),
    homepageUrl: getMockHomepageUrl(boothId), // 016 — 등록 mock 저장소가 정본
    publishedLayoutVersion: getMockPublishedVersion(boothId), // -898 — 게시 mock 저장소가 정본
  };
}

export async function putFacade(boothId: number, body: FacadePutRequest): Promise<BoothFacade> {
  if (boothId === LEASE_EXPIRED_BOOTH_ID) {
    throw apiError('BOOTH_LEASE_EXPIRED', '임대가 만료되어 편집할 수 없습니다.');
  }
  if (!THEME_CODES.includes(body.themeCode)) {
    throw fieldError('themeCode', '테마는 지정된 값 중 하나여야 합니다.');
  }
  if (body.primaryColor !== null && !isPaletteColor(body.primaryColor)) {
    // 형식(#RRGGBB) 검사는 팔레트 소속 검사에 포섭된다 — 12색이 전부 그 형식이다(계약 §6, PR #71)
    throw fieldError('primaryColor', '대표색은 팔레트 12색 중 하나여야 합니다.');
  }
  if (body.signText !== null && body.signText.length > 60) {
    throw fieldError('signText', '간판 문구는 60자 이하여야 합니다.');
  }
  if (body.logoUrl !== null && (!HTTPS_URL.test(body.logoUrl) || body.logoUrl.length > 2048)) {
    throw fieldError('logoUrl', '로고 URL은 https:// 형식 2048자 이하여야 합니다.');
  }
  // name 은 선택 필드다 — 생략은 "비우기" 가 아니라 **현재 이름 유지**다(booths.name NOT NULL).
  if (body.name !== undefined) {
    if (body.name.length < 1 || body.name.length > 100) {
      throw fieldError('name', '부스 이름은 1자 이상 100자 이하여야 합니다.');
    }
    names.set(boothId, body.name);
  }

  // BE와 동일한 대문자 정규화(PR #71) — 소문자 hex로 저장해도 대문자로 돌아온다
  // name 은 응답 밖이라 저장 객체에서 걷어낸다 — 남기면 facade 캐시에 계약 밖 필드가 섞인다.
  const { name: _name, ...facade } = body;
  const saved: BoothFacade = { ...facade, primaryColor: body.primaryColor?.toUpperCase() ?? null };
  facades.set(boothId, saved);
  persistFacades();
  return saved;
}
