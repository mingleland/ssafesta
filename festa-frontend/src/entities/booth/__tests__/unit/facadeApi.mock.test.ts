// facade mock이 서버 실동작(#58 C안 오류 봉투)과 같은 형태로 거부하는지 고정 —
// mock이 서버보다 느슨하면 FE가 field 경로를 mock 모드에서 못 밟는다(이 결함의 재발 방지).
// 출처: docs/08 §1.3-1(FIELD_INVALID·field 키), specs/005 contracts/layout-api.md §6
import { describe, expect, it } from 'vitest';
import { getBooth, putFacade } from '../../facadeApi.mock';
import { isApiError } from '../../../../shared/api/client';
import type { BoothFacade } from '../../types';

function validBody(): BoothFacade {
  return { themeCode: 'DEFAULT', primaryColor: null, signText: null, logoUrl: null };
}

async function rejection(body: BoothFacade): Promise<unknown> {
  try {
    await putFacade(1, body);
  } catch (e) {
    return e;
  }
  throw new Error('거부돼야 하는 요청이 통과했다');
}

describe('facadeApi.mock — FIELD_INVALID 오류 봉투', () => {
  it('검증 실패는 VALIDATION_FAILED + errors[0]={rule: FIELD_INVALID, field, message} 봉투다', async () => {
    const e = await rejection({ ...validBody(), primaryColor: 'not-a-color' });
    expect(isApiError(e)).toBe(true);
    if (!isApiError(e)) return;
    expect(e.code).toBe('VALIDATION_FAILED');
    expect(e.errors).toHaveLength(1);
    expect(e.errors[0].rule).toBe('FIELD_INVALID'); // rule은 규칙 어휘 — 필드명을 넣지 않는다(#58 §3)
    expect(e.errors[0].field).toBe('primaryColor');
    expect(e.errors[0].message).not.toBe('');
    expect(e.warnings).toEqual([]);
  });

  it('각 필드의 위반이 해당 field 값으로 온다', async () => {
    const cases: Array<[BoothFacade, string]> = [
      [{ ...validBody(), signText: 'x'.repeat(61) }, 'signText'],
      [{ ...validBody(), logoUrl: 'http://insecure.example' }, 'logoUrl'],
    ];
    for (const [body, field] of cases) {
      const e = await rejection(body);
      expect(isApiError(e) && e.errors[0].field).toBe(field);
    }
  });

  it('임대 만료 거부는 검증 봉투가 아니다 — code만으로 구분된다', async () => {
    try {
      await putFacade(999, validBody());
      throw new Error('만료 부스가 통과했다');
    } catch (e) {
      expect(isApiError(e) && e.code).toBe('BOOTH_LEASE_EXPIRED');
      expect(isApiError(e) && e.errors).toEqual([]);
    }
  });

  it('유효한 저장은 왕복 무손실이다', async () => {
    const body: BoothFacade = {
      themeCode: 'WARM',
      primaryColor: '#3B82F6',
      signText: 'AI 전시관',
      logoUrl: 'https://example.com/logo.png',
    };
    const saved = await putFacade(2, body);
    expect(saved).toEqual(body);
    const booth = await getBooth(2);
    expect(booth.facade).toEqual(body);
  });
});
