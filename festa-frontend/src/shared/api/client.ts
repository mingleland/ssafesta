// Spring 서버와 통신하는 단일 창구 — 인증 헤더·타임아웃·에러 형식을 여기서 통일
// API Client — docs/10_Frontend_설계서.md §5 + docs/08_Backend_API_명세서.md §1.3
// 오류 봉투는 전 endpoint 공통 (specs/005 contracts/layout-api.md §0, #17·#36 — 401/403 포함)

// errors·warnings 원소. 서버가 objectId를 null로 두면 키 자체를 생략한다(@JsonInclude(NON_NULL))
export type ApiErrorDetail = { rule: string; objectId?: string; message: string };

// errors·warnings는 항상 배열 — 서버가 빈 배열을 보장하고, 이 파일의 fallback도 같은 형태를 유지한다.
// 분기는 code로만 한다(문자열 매칭·rule 분기 금지 — rule 전체 목록이 계약 문서에 없다, FE/research.md R-12)
export type ApiError = {
  code: string;
  message: string;
  requestId?: string; // 서버 응답에는 항상 있으나(X-Request-Id 헤더와 동일) 네트워크 단계 실패 시 없을 수 있다
  errors: ApiErrorDetail[];
  warnings: ApiErrorDetail[];
};

// throw된 값이 오류 봉투인지 식별 — fetch 자체 실패(TypeError·AbortError)와 구분하는 용도
export function isApiError(e: unknown): e is ApiError {
  return (
    typeof e === 'object' &&
    e !== null &&
    typeof (e as ApiError).code === 'string' &&
    typeof (e as ApiError).message === 'string' &&
    Array.isArray((e as ApiError).errors) &&
    Array.isArray((e as ApiError).warnings)
  );
}

let accessToken: string | null = null;

// AT는 JS 메모리에 보관 (docs/26 결정) — 새로고침 시 소실은 spec 001에서 처리
export function setAccessToken(token: string | null): void {
  accessToken = token;
}

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';
const DEFAULT_TIMEOUT_MS = 15_000;

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers,
    signal: init.signal ?? AbortSignal.timeout(DEFAULT_TIMEOUT_MS),
  });

  // 401 처리·Refresh: docs/26 AT/RT 쿠키 속성·재발급 endpoint 미확정 — spec 001에서 확정 후 구현
  // TODO: docs/26 AT/RT 확정 후 401 인터셉트·재발급 흐름 추가

  if (!response.ok) {
    const requestId = response.headers.get('X-Request-Id') ?? undefined;
    let error: ApiError;
    try {
      const body = (await response.json()) as Partial<ApiError>;
      error = {
        code: body.code ?? 'UNKNOWN',
        message: body.message ?? response.statusText,
        requestId: body.requestId ?? requestId,
        // 비계약 응답(프록시 오류 등)이 errors.length를 터뜨리지 않도록 배열 보정
        errors: Array.isArray(body.errors) ? body.errors : [],
        warnings: Array.isArray(body.warnings) ? body.warnings : [],
      };
    } catch {
      error = { code: 'UNKNOWN', message: response.statusText, requestId, errors: [], warnings: [] };
    }
    throw error;
  }

  if (response.status === 204) return undefined as T;
  return response.json();
}
