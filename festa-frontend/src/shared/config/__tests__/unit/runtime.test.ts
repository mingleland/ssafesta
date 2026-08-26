// runtime config fallback 고정 — 런타임 주입이 실패했을 때 조용히 빈 base로 떨어지지 않는 것이 요지다.
import { describe, expect, it } from 'vitest';
import { resolveApiBaseUrl } from '../../runtime';

describe('resolveApiBaseUrl', () => {
  it('런타임 값이 있으면 그것을 쓴다 — 배포 이미지의 정상 경로', () => {
    expect(resolveApiBaseUrl('https://api.example.test', 'http://localhost:8080')).toBe('https://api.example.test');
  });

  it('런타임 값이 없으면 빌드타임 값으로 내려간다 — npm run dev의 정상 경로', () => {
    expect(resolveApiBaseUrl(undefined, 'http://localhost:8080')).toBe('http://localhost:8080');
  });

  it('런타임 값이 빈 문자열이어도 빌드타임 값으로 내려간다 — 주입이 비어 온 경우', () => {
    expect(resolveApiBaseUrl('', 'http://localhost:8080')).toBe('http://localhost:8080');
  });

  it('둘 다 없으면 상대 경로(빈 문자열) — same-origin 배치의 기본값', () => {
    expect(resolveApiBaseUrl(undefined, undefined)).toBe('');
  });
});
