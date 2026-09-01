// 로컬 개발용 메모리 mock — facade PUT의 서버 검증(§6)과 만료 거부(§6 409)를 재현한다.
// VITE_USE_MOCK=true일 때 facadeApi.ts 대신 이 모듈을 쓴다(선택은 facadeApi.select.ts에서 한다).
// 출처: specs/005-booth-studio-layout/contracts/layout-api.md §6·§7

import type { ApiError } from '../../shared/api/client';
import { THEME_CODES, isPaletteColor } from './types';
import type { BoothDetail, BoothFacade, FacadePutRequest } from './types';
import { getMockHomepageUrl } from './homepageApi.mock';

// layout mock(entities/layout/api.mock.ts)과 같은 sentinel 값 — 임대 만료 UX 수동 검증용.
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

export async function getBooth(boothId: number): Promise<BoothDetail> {
  return {
    boothId,
    name: 'AI 프로젝트 전시관',
    leaseStatus: boothId === LEASE_EXPIRED_BOOTH_ID ? 'EXPIRED' : 'ACTIVE',
    facade: facades.get(boothId) ?? defaultFacade(),
    homepageUrl: getMockHomepageUrl(boothId), // 016 — 등록 mock 저장소가 정본
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

  // BE와 동일한 대문자 정규화(PR #71) — 소문자 hex로 저장해도 대문자로 돌아온다
  const saved: BoothFacade = { ...body, primaryColor: body.primaryColor?.toUpperCase() ?? null };
  facades.set(boothId, saved);
  persistFacades();
  return saved;
}
