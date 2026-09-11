// @vitest-environment jsdom
// S15P21A604-630 — 데이터 탭 "이미지 추가"가 한 번에 여러 파일을 선택할 수 있게 됐다. 핵심
// 회귀 방어는 "순차 처리가 유지되는가"다 — uploadAssets()를 Promise.all/forEach로 병렬화하면
// nextStableId가 await 이전 상태를 보고 계산돼 여러 파일이 같은 assetId를 배정받고, 로컬
// 저장소에서 같은 키를 두고 blob이 서로 덮어쓰는 조용한 오염이 생긴다(조사 기록 참고).
// 그래서 mock GameAssetRepository.save가 실제로 받는 suggestedAssetId가 매 호출 겹치지
// 않는지를 직접 확인한다. 배치 요약 토스트·진행 카운터·1장일 때의 기존 동작·부분 실패·
// 300개 상한도 함께 검증한다. "타일셋 추가"는 이 티켓의 다중 선택 대상이 아니므로 회귀만
// 방어한다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameAssetRepository } from '../../studio/assets/localAssetRepository.ts';
import type { AssetReference, GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const GAME_ID = 630;

const createMockRepository = (
  onSave?: (file: File) => void,
): { readonly repository: GameAssetRepository; readonly savedIds: string[] } => {
  const savedIds: string[] = [];
  const repository: GameAssetRepository = {
    save: async (gameId, { file, kind, suggestedAssetId }) => {
      onSave?.(file);
      const assetId = suggestedAssetId ?? 'fallback';
      savedIds.push(assetId);
      const asset: AssetReference = { id: assetId, kind, source: `asset://local/${gameId}/${assetId}` };
      return { asset, originalName: file.name };
    },
    resolve: async () => null,
    delete: async () => undefined,
  };
  return { repository, savedIds };
};

const openDataTab = (repository: GameAssetRepository | null, project: GameProject = createStarterProject(GAME_ID)) => {
  const utils = render(
    <MemoryRouter>
      <GameStudioShell assetRepository={repository} gameId={GAME_ID} initialProject={project} publisher={null} repository={null} />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('button', { name: '데이터' }));
  return utils;
};

const fileInputs = (container: HTMLElement): readonly HTMLInputElement[] => (
  Array.from(container.querySelectorAll('input[type="file"]')) as HTMLInputElement[]
);

const imageInput = (container: HTMLElement): HTMLInputElement => {
  const input = fileInputs(container).find((candidate) => candidate.accept.includes('image/gif'));
  if (input === undefined) throw new Error('이미지 추가 input을 찾을 수 없다');
  return input;
};

const tilesetInput = (container: HTMLElement): HTMLInputElement => {
  const input = fileInputs(container).find((candidate) => candidate.accept === 'image/png,image/jpeg,image/webp');
  if (input === undefined) throw new Error('타일셋 추가 input을 찾을 수 없다');
  return input;
};

const selectFiles = (input: HTMLInputElement, files: readonly File[]) => {
  Object.defineProperty(input, 'files', { configurable: true, value: files });
  fireEvent.change(input);
};

describe('GameStudioShell — 데이터 탭 이미지 다중 업로드(S15P21A604-630)', () => {
  it('이미지 input은 multiple을 받고, 타일셋 input은 그대로 단일이다', () => {
    const { repository } = createMockRepository();
    const { container } = openDataTab(repository);
    expect(imageInput(container).multiple).toBe(true);
    expect(tilesetInput(container).multiple).toBe(false);
  });

  it('여러 파일을 한 번에 선택하면 순차로 처리되어 저장소가 받는 assetId가 겹치지 않는다', async () => {
    const { repository, savedIds } = createMockRepository();
    const { container } = openDataTab(repository);

    selectFiles(imageInput(container), [
      new File(['a'], 'bg1.png', { type: 'image/png' }),
      new File(['b'], 'bg2.png', { type: 'image/png' }),
      new File(['c'], 'bg3.png', { type: 'image/png' }),
    ]);

    await screen.findByText('3개 자산을 추가했습니다.');
    expect(savedIds).toHaveLength(3);
    expect(new Set(savedIds).size).toBe(3);
  });

  it('처리 중 "n/총" 카운터가 보이고 끝나면 사라진다', async () => {
    const { repository } = createMockRepository();
    const { container } = openDataTab(repository);

    selectFiles(imageInput(container), [
      new File(['a'], 'bg1.png', { type: 'image/png' }),
      new File(['b'], 'bg2.png', { type: 'image/png' }),
    ]);

    await screen.findByText('2개 자산을 추가했습니다.');
    expect(screen.queryByText(/처리중/)).toBeNull();
  });

  it('파일 1장만 선택하면 기존과 동일하게 개별 토스트만 뜨고 배치 요약은 뜨지 않는다', async () => {
    const { repository } = createMockRepository();
    const { container } = openDataTab(repository);

    selectFiles(imageInput(container), [new File(['a'], 'solo.png', { type: 'image/png' })]);

    await screen.findByText('solo.png을 IMAGE 자산으로 추가했습니다.');
    expect(screen.queryByText(/개 자산을 추가했습니다/)).toBeNull();
  });

  it('일부 파일이 실패해도 나머지는 계속 처리되고 배치 요약에 실패 사유가 남는다', async () => {
    const savedIds: string[] = [];
    const repository: GameAssetRepository = {
      save: async (gameId, { file, kind, suggestedAssetId }) => {
        if (file.name === 'bad.png') throw new Error('mock failure');
        const assetId = suggestedAssetId ?? 'fallback';
        savedIds.push(assetId);
        return { asset: { id: assetId, kind, source: `asset://local/${gameId}/${assetId}` }, originalName: file.name };
      },
      resolve: async () => null,
      delete: async () => undefined,
    };
    const { container } = openDataTab(repository);

    selectFiles(imageInput(container), [
      new File(['a'], 'ok1.png', { type: 'image/png' }),
      new File(['b'], 'bad.png', { type: 'image/png' }),
      new File(['c'], 'ok2.png', { type: 'image/png' }),
    ]);

    await screen.findByText(/2개 추가, 1개 실패: bad\.png\(추가 실패\)/);
    expect(savedIds).toHaveLength(2);
  });

  it('자산 300개 상한에 도달하면 상한까지만 처리하고 초과분은 실패로 안내한다', async () => {
    const { repository, savedIds } = createMockRepository();
    const starter = createStarterProject(GAME_ID);
    // S15P21A604-626 조사에서 확인된 패턴 그대로: addAssetReference(→validated→
    // parseGameProject)를 298번 루프에서 부르면 매 호출이 지금까지 쌓인 배열 전체를 다시
    // 검증해 O(n²)이 되고, CI처럼 CPU가 눌린 환경에서는 testTimeout(5000ms)을 넘길 수 있다
    // (로컬 22코어에서는 통과했지만 CI 컨테이너에서 timeout). 배열을 한 번에 이어붙이고
    // 검증은 GameStudioShell 마운트 시 1회만 타도록 해서 O(n)으로 낮춘다.
    const stressAssets: readonly AssetReference[] = Array.from(
      { length: 300 - starter.assets.length - 2 },
      (_, index) => ({ id: `stress${index}`, kind: 'AUDIO', source: `asset://local/${GAME_ID}/stress${index}` }),
    );
    const project: GameProject = { ...starter, assets: [...starter.assets, ...stressAssets] };
    expect(project.assets.length).toBe(298);
    const { container } = openDataTab(repository, project);

    selectFiles(imageInput(container), [
      new File(['a'], 'bg1.png', { type: 'image/png' }),
      new File(['b'], 'bg2.png', { type: 'image/png' }),
      new File(['c'], 'bg3.png', { type: 'image/png' }),
      new File(['d'], 'bg4.png', { type: 'image/png' }),
      new File(['e'], 'bg5.png', { type: 'image/png' }),
    ]);

    await screen.findByText(/2개 추가, 3개 실패:.*자산 300개 상한 초과/);
    expect(savedIds).toHaveLength(2);
  });
});
