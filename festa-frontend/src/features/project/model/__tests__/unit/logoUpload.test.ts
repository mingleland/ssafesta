import { describe, expect, it } from 'vitest';
import {
  PROJECT_LOGO_PREVIEW_MAX_BYTES,
  validateProjectLogo,
} from '../../logoUpload';

describe('project logo preview validation', () => {
  it('PNG/JPEG/WebP 파일만 허용한다', () => {
    expect(validateProjectLogo(new File(['x'], 'logo.png', { type: 'image/png' }))).toBeNull();
    expect(validateProjectLogo(new File(['x'], 'logo.svg', { type: 'image/svg+xml' })))
      .toBe('PNG, JPG, WebP 이미지만 선택할 수 있습니다.');
  });

  it('미리보기 안전 상한보다 큰 파일을 거부한다', () => {
    const file = new File([new Uint8Array(PROJECT_LOGO_PREVIEW_MAX_BYTES + 1)], 'large.webp', {
      type: 'image/webp',
    });
    expect(validateProjectLogo(file)).toBe('이미지는 5MB 이하만 선택할 수 있습니다.');
  });
});
