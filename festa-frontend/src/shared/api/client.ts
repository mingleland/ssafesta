// API Client — docs/10_Frontend_설계서.md §5 + docs/08_Backend_API_명세서.md §1

export type ApiError = { code: string; message: string; requestId?: string };

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
    let error: ApiError;
    try {
      error = await response.json();
    } catch {
      error = { code: 'UNKNOWN', message: response.statusText };
    }
    throw error;
  }

  if (response.status === 204) return undefined as T;
  return response.json();
}
