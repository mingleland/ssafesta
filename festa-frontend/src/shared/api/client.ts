// Spring 서버와 통신하는 단일 창구 — 인증 헤더·타임아웃·에러 형식을 여기서 통일
// API Client — docs/10_Frontend_설계서.md §5 + docs/08_Backend_API_명세서.md §1.3
// 오류 봉투는 전 endpoint 공통 (specs/005 contracts/layout-api.md §0, #17·#36 — 401/403 포함)
import { apiBaseUrl } from '../config/runtime';

// errors·warnings 원소. objectId·field는 서버가 값 없으면 키 자체를 생략한다(@JsonInclude(NON_NULL)).
// objectId는 배치된 오브젝트, field는 요청 필드 경로 — 가리키는 대상이 달라 합치지 않는다(docs/08 §1.3, #58 C안).
export type ApiErrorDetail = { rule: string; objectId?: string; field?: string; message: string };

// errors·warnings는 항상 배열 — 서버가 빈 배열을 보장하고, 이 파일의 fallback도 같은 형태를 유지한다.
// rule은 항상 규칙 어휘다(#58 C안, PR #71) — 전역 rule은 docs/08 §1.3-1(FIELD_INVALID 등), Layout rule은
// contracts/layout-api.md 소유. 분기는 code로 하고, rule 분기는 필요한 소비자가 생길 때 화이트리스트로 연다.
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

// TODO(013a-AT):
// Unity credential handoff contract unresolved.
// Do not send credentials until token type, timing,
// receiver, and refresh behavior are agreed.
//
// 이 getter는 FE 내부 read boundary일 뿐이다 — 013a WebGL Host의 어떤 lifecycle
// (인스턴스 생성·onWorldGateReady 등)에도 연결하지 않는다. Unity에 무엇을(AT 원본 vs
// 단수명 Unity 전용 token) · 언제 · 어떤 방식(SendMessage/jslib)으로 전달할지,
// 갱신은 어떻게 반영할지 전부 미결이다(FE.md:71). RT는 이 경계를 절대 넘기지 않는다.
export function getAccessToken(): string | null {
  return accessToken;
}

const DEFAULT_TIMEOUT_MS = 15_000;

// 401 인터셉트(spec 001, docs/26 AT/RT 확정분) — 등록 지점만 여기 둔다. 실 refresh 로직은
// features/auth가 주입한다(순환 import 방지: client.ts는 entities/features를 모른다).
// 반환값 true면 원요청을 1회 재시도한다. false면 그대로 오류를 던진다.
type UnauthorizedHandler = () => Promise<boolean>;
let unauthorizedHandler: UnauthorizedHandler | null = null;

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler;
}

// skipAuthRetry: 401 인터셉트 재시도 자체(그리고 refresh 요청 자신)에 다시 인터셉트가 걸려
// 무한 루프가 되는 것을 막는 내부 플래그 — 호출부(entities/auth/api.ts의 refresh)가 명시한다.
export type ApiInit = RequestInit & { skipAuthRetry?: boolean };

export async function api<T>(path: string, init: ApiInit = {}): Promise<T> {
  const { skipAuthRetry, ...rest } = init;
  const headers = new Headers(rest.headers);
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  if (rest.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

  const response = await fetch(`${apiBaseUrl()}${path}`, {
    ...rest,
    headers,
    signal: rest.signal ?? AbortSignal.timeout(DEFAULT_TIMEOUT_MS),
  });

  if (response.status === 401 && !skipAuthRetry && unauthorizedHandler) {
    const recovered = await unauthorizedHandler();
    // skipAuthRetry:true로 재시도 — 재시도 응답이 다시 401이어도 인터셉트를 또 태우지 않는다(상한 1회)
    if (recovered) return api<T>(path, { ...init, skipAuthRetry: true });
  }

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
