// FACADE_PALETTE 12색이 계약 §6과 어긋나면 스와치 전체가 400 제조기가 된다 — 전사 정확성을 고정.
// 출처: specs/005-booth-studio-layout/contracts/layout-api.md §6(2026-08-23 확정, #17)
import { describe, expect, it } from 'vitest';
import { FACADE_PALETTE, isPaletteColor } from '../../types';
import { isApiError } from '../../../../shared/api/client';
import { putFacade } from '../../facadeApi.mock';
import type { BoothFacade } from '../../types';

// 계약 §6 표의 독립 전사 — types.ts와 같은 원본을 두 경로로 옮겨 적어 한쪽 오타를 잡는다
const CONTRACT_TABLE: Array<[string, string]> = [
  ['RED', '#EF4444'],
  ['ORANGE', '#F97316'],
  ['AMBER', '#F59E0B'],
  ['YELLOW', '#EAB308'],
  ['LIME', '#84CC16'],
  ['GREEN', '#22C55E'],
  ['TEAL', '#14B8A6'],
  ['CYAN', '#06B6D4'],
  ['BLUE', '#3B82F6'],
  ['INDIGO', '#6366F1'],
  ['PURPLE', '#A855F7'],
  ['PINK', '#EC4899'],
];

function body(primaryColor: string | null): BoothFacade {
  return { themeCode: 'DEFAULT', primaryColor, signText: null, logoUrl: null };
}

describe('FACADE_PALETTE — 계약 §6 전사', () => {
  it('12색이 code·hex·순서까지 계약 표와 일치한다', () => {
    expect(FACADE_PALETTE.map((c) => [c.code, c.hex])).toEqual(CONTRACT_TABLE);
  });

  it('hex는 전부 대문자 #RRGGBB — 소속 판정이 대문자 비교라 전제가 깨지면 스와치 선택 표시가 죽는다', () => {
    for (const c of FACADE_PALETTE) {
      expect(c.hex).toMatch(/^#[0-9A-F]{6}$/);
    }
  });

  it('isPaletteColor는 대소문자를 무시한다(BE 정규화와 동일)', () => {
    expect(isPaletteColor('#3B82F6')).toBe(true);
    expect(isPaletteColor('#3b82f6')).toBe(true);
    expect(isPaletteColor('#1677C8')).toBe(false); // 구 브랜드 블루 — #17에서 팔레트 제외 확정
  });
});

describe('facadeApi.mock — 팔레트 검증(PR #71 BE 동작 재현)', () => {
  it('팔레트 밖 hex는 형식이 맞아도 FIELD_INVALID로 거부한다', async () => {
    try {
      await putFacade(1, body('#1677C8'));
      throw new Error('팔레트 밖 값이 통과했다');
    } catch (e) {
      expect(isApiError(e) && e.errors[0]?.field).toBe('primaryColor');
    }
  });

  it('소문자 hex는 대문자로 정규화해 저장한다', async () => {
    const saved = await putFacade(3, body('#3b82f6'));
    expect(saved.primaryColor).toBe('#3B82F6');
  });

  it('null(색 없음)은 그대로 통과한다', async () => {
    const saved = await putFacade(4, body(null));
    expect(saved.primaryColor).toBeNull();
  });
});
