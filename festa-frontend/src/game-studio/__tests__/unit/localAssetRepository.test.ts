import { describe, expect, it } from 'vitest';
import { validateAssetFile } from '../../studio/assets/localAssetRepository.ts';

describe('Game Studio v1 asset upload boundary', () => {
  it.each(['image/png', 'image/jpeg', 'image/gif', 'image/webp'])('accepts supported image MIME %s', (type) => {
    expect(() => validateAssetFile('IMAGE', { size: 1024, type })).not.toThrow();
  });

  it('rejects SVG even though it is an image MIME', () => {
    expect(() => validateAssetFile('IMAGE', { size: 1024, type: 'image/svg+xml' })).toThrow(/PNG/);
  });

  it('rejects v1 audio and files larger than 5 MiB', () => {
    expect(() => validateAssetFile('AUDIO', { size: 1024, type: 'audio/mpeg' })).toThrow(/오디오/);
    expect(() => validateAssetFile('IMAGE', { size: 5 * 1024 * 1024 + 1, type: 'image/png' })).toThrow(/5MB/);
  });
});
