import { describe, expect, it } from 'vitest';
import { findPublishBlockers } from '../../studio/ports/publishValidation.ts';
import { cloneMinimalGameProject } from '../fixtures/minimalGameProject.ts';

describe('GameProject publish preflight', () => {
  it('accepts builtin and server-stable Asset references', () => {
    const project = cloneMinimalGameProject();
    project.assets.push({ id: 'server_image', kind: 'IMAGE', source: 'asset://games/123/assets/99' });
    expect(findPublishBlockers(project)).toEqual([]);
  });

  it('blocks local-only replacements before a server Publish request', () => {
    const project = cloneMinimalGameProject();
    project.assets.push({ id: 'local_image', kind: 'IMAGE', source: 'asset://local/123/local_image' });
    expect(findPublishBlockers(project)).toEqual([
      expect.objectContaining({ code: 'LOCAL_ASSET', assetId: 'local_image' }),
    ]);
  });

  it('reports every editor location that uses a local-only asset', () => {
    const project = cloneMinimalGameProject();
    const keyImage = project.assets.find((asset) => asset.id === 'keyImage');
    if (keyImage === undefined) throw new Error('keyImage fixture missing');
    keyImage.source = 'asset://local/123/keyImage';

    expect(findPublishBlockers(project)[0]).toMatchObject({
      assetId: 'keyImage',
      locations: ['아이템 · 작은 열쇠', '잠긴 방 · roomKey 이미지'],
    });
  });

  it.each(['data:image/png;base64,AAA', 'blob:http://localhost/id', 'file:///tmp/a.png', 'https://temporary.example/a.png'])(
    'blocks unstable source %s',
    (source) => {
      const project = cloneMinimalGameProject();
      project.assets.push({ id: 'unstable_image', kind: 'IMAGE', source });
      expect(findPublishBlockers(project)[0]).toMatchObject({ code: 'UNSTABLE_ASSET_SOURCE' });
    },
  );
});
