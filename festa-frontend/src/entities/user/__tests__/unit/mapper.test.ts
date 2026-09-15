import { describe, expect, it } from 'vitest';
import { toNicknameErrorKind, toProviderIds } from '../../mapper';
import type { ApiError } from '../../../../shared/api/client';

function apiError(code: string): ApiError {
  return { code, message: '', errors: [], warnings: [] };
}

describe('toProviderIds', () => {
  it('BE 대문자 enum 이름을 소문자 id 로 변환한다', () => {
    expect(toProviderIds(['GOOGLE', 'KAKAO'])).toEqual(['google', 'kakao']);
  });

  it('게스트(빈 배열)와 미지 provider 를 안전 처리한다', () => {
    expect(toProviderIds([])).toEqual([]);
    expect(toProviderIds(['NAVER'])).toEqual([]);
  });

  it('BE 에 SSAFY 가 추가되면 변환만으로 수용된다', () => {
    expect(toProviderIds(['SSAFY'])).toEqual(['ssafy']);
  });
});

describe('toNicknameErrorKind', () => {
  it('BE 오류 코드를 계약 어휘로 매핑한다', () => {
    expect(toNicknameErrorKind(apiError('NICKNAME_INVALID'))).toBe('invalid');
    expect(toNicknameErrorKind(apiError('NICKNAME_DUPLICATED'))).toBe('duplicated');
  });

  it('그 외 오류·비봉투는 network', () => {
    expect(toNicknameErrorKind(apiError('UNKNOWN'))).toBe('network');
    expect(toNicknameErrorKind(new TypeError('fetch failed'))).toBe('network');
  });
});
