// returnTo 저장/소비·open redirect 방어 회귀 방어(S-20260823-80, G-2).
// vitest 환경이 'node'라 sessionStorage가 없다 — 최소 Map 기반 폴리필로 대체한다
// (features/interaction/__tests__/unit/dispatcher.test.ts의 window 폴리필과 동일 취지).
class MemoryStorage {
  private map = new Map<string, string>();
  getItem(key: string): string | null {
    return this.map.has(key) ? this.map.get(key)! : null;
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value);
  }
  removeItem(key: string): void {
    this.map.delete(key);
  }
  clear(): void {
    this.map.clear();
  }
}
(globalThis as unknown as { sessionStorage: Storage }).sessionStorage = new MemoryStorage() as unknown as Storage;

import { beforeEach, describe, expect, it } from 'vitest';
import { consumeReturnTo, isSafeReturnTo, saveReturnTo } from '../../returnTo';

beforeEach(() => {
  sessionStorage.clear();
});

describe('isSafeReturnTo', () => {
  it('앱 내부 경로(단일 선행 슬래시)는 허용한다', () => {
    expect(isSafeReturnTo('/app/home')).toBe(true);
    expect(isSafeReturnTo('/app/studio/abc?x=1#y')).toBe(true);
  });

  it('빈 문자열·슬래시로 시작하지 않는 값은 거부한다', () => {
    expect(isSafeReturnTo('')).toBe(false);
    expect(isSafeReturnTo('app/home')).toBe(false);
    expect(isSafeReturnTo(' /evil.com')).toBe(false); // 앞 공백 — 슬래시로 시작하지 않음
  });

  it('외부 URL(스킴 포함)은 거부한다', () => {
    expect(isSafeReturnTo('http://evil.com')).toBe(false);
    expect(isSafeReturnTo('https://evil.com')).toBe(false);
    expect(isSafeReturnTo('javascript:alert(1)')).toBe(false);
  });

  it('protocol-relative 경로(//evil.com)는 거부한다', () => {
    expect(isSafeReturnTo('//evil.com')).toBe(false);
    expect(isSafeReturnTo('///evil.com')).toBe(false);
  });

  it('백슬래시 정규화 우회(브라우저가 \\를 /로 바꿔 protocol-relative가 되는 트릭)는 거부한다', () => {
    expect(isSafeReturnTo('/\\evil.com')).toBe(false);
    expect(isSafeReturnTo('/\\/evil.com')).toBe(false);
  });
});

describe('saveReturnTo·consumeReturnTo', () => {
  it('안전한 경로를 저장했다가 1회 소비하고, 소비 후에는 남지 않는다', () => {
    saveReturnTo('/app/studio/abc');
    expect(consumeReturnTo()).toBe('/app/studio/abc');
    expect(consumeReturnTo()).toBe('/app/home'); // 이미 소비됨 — 기본값
  });

  it('안전하지 않은 경로는 애초에 저장하지 않는다', () => {
    saveReturnTo('https://evil.com');
    expect(consumeReturnTo()).toBe('/app/home');
  });

  it('저장된 값이 없으면 기본 목적지(/app/home)로 떨어진다', () => {
    expect(consumeReturnTo()).toBe('/app/home');
  });
});
