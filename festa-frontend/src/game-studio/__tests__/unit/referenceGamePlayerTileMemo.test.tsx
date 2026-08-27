// @vitest-environment jsdom
// S15P21A604-263 회귀 방어 — 최대 fixture(10,000 타일)에서 runtime tick·이동 리렌더마다
// 타일마다 project.assets.find(tileset lookup)를 반복 실행하던 것이 병목의 큰 비중이었다.
// TileLayers를 memo로 분리한 뒤에는 부모(ReferenceGamePlayer)가 리렌더돼도 tileset lookup이
// 다시 실행되지 않아야 한다 — DOM 노드 개수만 보면 React가 key로 재사용해 버려 구분이 안 된다
// (실제로 memo 제거 후에도 노드 개수/참조 동일성 검사는 통과했다), 그래서 lookup 호출 횟수로 검증한다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { createEditorStressProject } from '../../studio/model/createEditorStressProject.ts';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import * as tilesetVisual from '../../studio/assets/tilesetVisual.ts';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

// 실제 호출부(PublishedGameSurface)는 useResolvedAssetUrls가 내용 불변이면 참조를 그대로
// 반환해 assetUrls를 안정적으로 넘긴다 — 여기서도 매 렌더 새 객체가 되지 않도록 고정한다.
// (ReferenceGamePlayer의 assetUrls 기본값 `{}`를 그대로 썼더니 이 자체가 매번 새 참조라
//  memo가 항상 깨졌다 — 실제 경로가 아닌 테스트 설정의 함정이었다.)
const STABLE_ASSET_URLS = {};

describe('ReferenceGamePlayer — 타일 레이어 메모이제이션', () => {
  it('플레이어 이동으로 인한 리렌더가 레이어당 한 번인 tileset lookup을 다시 실행하지 않는다', () => {
    const resolveSpy = vi.spyOn(tilesetVisual, 'resolveTilesetVisual');
    const project = createEditorStressProject(9302);
    render(
      <ReferenceGamePlayer
        assetUrls={STABLE_ASSET_URLS}
        mode="PREVIEW"
        onExit={() => undefined}
        project={project}
        sessionPort={createPreviewGameSessionPort()}
      />,
    );

    // fixture는 tileLayers 1개 — memo가 없으면 여기서 이미 타일 수(10,000)만큼 불렸을 것이다.
    expect(resolveSpy).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(window, { key: 'ArrowRight' });
    fireEvent.keyDown(window, { key: 'ArrowDown' });
    fireEvent.keyDown(window, { key: 'ArrowLeft' });

    // 플레이어 이동 3회로 부모는 3번 더 리렌더됐지만, tileLayers/assets/assetUrls 참조가
    // 그대로라 memo가 스킵해 lookup 호출 수는 그대로여야 한다.
    expect(resolveSpy).toHaveBeenCalledTimes(1);
  });
});
