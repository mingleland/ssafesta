// @vitest-environment jsdom
// 팔레트 기능형 회귀 (S15P21A604-783, GitLab #201).
//
// **무엇을 잠그는가.** "기능 오브젝트가 실물 자산 유무와 무관하게 팔레트에 선다".
// 예전 구조는 `hasRealAssets ? 실자산 : 하드코딩` 이라는 배타 분기였고, manifest 는 assetCode 가
// 붙은 자산만 담아 기능형을 실을 수 없다. -741 이 runtime asset 을 자동 생성하게 되자 실물이
// 항상 존재하게 됐고, 그 순간 기능형 8종이 통째로 사라져 AI 직원을 놓을 방법 자체가 없어졌다.
//
// 그래서 AI_AGENT 하나만 보지 않는다 — 8종을 **objectType 단위로 하나씩** 단언한다. 한 종만 보면
// 다음에 누가 목록을 줄여도 red 가 안 난다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { AssetPalette } from '../../ui/shell/AssetPalette';
import { FUNCTIONAL_PALETTE, LAYOUT_PALETTE } from '../../model/visualAssets';
import type { BoothAssetEntry } from '../../model/boothAssetManifest';
import type { ObjectType } from '../../../../entities/layout/types';

afterEach(cleanup);

const entry = (over: Partial<BoothAssetEntry>): BoothAssetEntry => ({
  assetCode: 'DISP_BOX_01',
  objectType: 'DECORATION',
  typeDefault: true,
  url: 'DISP_BOX_01.glb',
  thumbnail: null,
  bytes: 1,
  triangles: 1,
  bounds: { min: [-0.3, 0, -0.3], max: [0.3, 1.6085, 0.3] },
  source: { fbx: 'x.FBX', unitScale: 0.0254, upAxis: 'zUp', rawBounds: { min: [0, 0, 0], max: [1, 1, 1] } },
  ...over,
});

/** -741 이 굽는 10종 중 대표 — 카테고리가 갈리는 것만 골랐다. 실물이 "있다" 를 만들면 충분하다 */
const REAL_ASSETS: BoothAssetEntry[] = [
  entry({}),
  entry({ assetCode: 'FURN_CHAIR_01_WHITE', objectType: 'FURNITURE' }),
  entry({ assetCode: 'BOOTH_KIOSK_SURVEY', objectType: 'SURVEY_KIOSK' }),
  entry({ assetCode: 'STRUCT_PANEL_01', objectType: 'DECORATION', typeDefault: false }),
];

function renderPalette(over: Partial<React.ComponentProps<typeof AssetPalette>> = {}) {
  const onAddObject = vi.fn();
  const onAddAsset = vi.fn();
  render(
    <AssetPalette
      mode="layout"
      currentCount={0}
      maxObjects={12}
      catalog={[]}
      assets={REAL_ASSETS}
      activePresetId={null}
      onAddObject={onAddObject}
      onAddAsset={onAddAsset}
      onApplyTemplate={vi.fn()}
      onPickPreset={vi.fn()}
      {...over}
    />,
  );
  return { onAddObject, onAddAsset };
}

/** 라벨로 팔레트 버튼을 찾는다 — 화면에서 사용자가 집는 것과 같은 경로 */
function thumb(label: string): HTMLButtonElement {
  return screen.getByRole('button', { name: new RegExp(label) }) as HTMLButtonElement;
}

describe('기능 오브젝트는 실물 자산이 있어도 사라지지 않는다 (#201)', () => {
  const FUNCTIONAL: Array<[ObjectType, string]> = [
    ['AI_AGENT', 'AI 직원'],
    ['PROJECT_PANEL', '그래픽 패널'],
    ['SURVEY_KIOSK', '설문 키오스크'],
    ['CONSULTATION_DESK', '상담 데스크'],
    ['VIDEO_SCREEN', '영상 스크린'],
    ['LIKE_VOTE', '좋아요 스탠드'],
    ['RECRUITMENT_BOARD', '채용 보드'],
    ['LAPTOP', '노트북'],
  ];

  it.each(FUNCTIONAL)('%s 가 실물 자산 환경에서도 팔레트에 있다', (objectType, label) => {
    const { onAddObject } = renderPalette();
    fireEvent.click(thumb(label));
    expect(onAddObject).toHaveBeenCalledTimes(1);
    const item = onAddObject.mock.calls[0][0];
    expect(item.objectType).toBe(objectType);
    // assetCode 가 붙으면 장식형이 된다 — 서버가 외형 코드로 취급해 콘텐츠 연결이 끊긴다
    expect(item.assetCode).toBeUndefined();
  });

  it.each(FUNCTIONAL)('%s 는 manifest 가 비어도 팔레트에 있다', (_objectType, label) => {
    renderPalette({ assets: [] });
    expect(thumb(label)).toBeTruthy();
  });

  it('기능형 목록이 8종 그대로다 — 조용히 줄어들면 red', () => {
    expect(FUNCTIONAL_PALETTE.items).toHaveLength(8);
    expect(FUNCTIONAL_PALETTE.items.every((i) => i.assetCode === undefined)).toBe(true);
  });

  it('장식 팔레트에는 assetCode 없는 항목이 남아 있지 않다 — 남으면 실물 환경에서 또 사라진다', () => {
    const bare = LAYOUT_PALETTE.flatMap((s) => s.items).filter((i) => i.assetCode === undefined);
    expect(bare).toEqual([]);
  });
});

describe('두 갈래가 서로를 밀어내지 않는다', () => {
  it('실물 자산과 기능형이 함께 보인다', () => {
    renderPalette();
    expect(thumb('AI 직원')).toBeTruthy();
    expect(screen.getByLabelText('에셋 검색')).toBeTruthy();
  });

  it('실물 클릭은 onAddAsset, 기능형 클릭은 onAddObject — payload 갈래가 유지된다', () => {
    const { onAddObject, onAddAsset } = renderPalette();
    fireEvent.click(thumb('AI 직원'));
    expect(onAddObject).toHaveBeenCalledTimes(1);
    expect(onAddAsset).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTitle(/의자 01 WHITE 추가|CHAIR/));
    expect(onAddAsset).toHaveBeenCalledTimes(1);
    expect(onAddAsset.mock.calls[0][0]).toMatchObject({ assetCode: 'FURN_CHAIR_01_WHITE' });
  });

  it('SURVEY_KIOSK 는 기능형 1 + 실물 1 로 각각 한 번씩만 — 중복 증식이 아니다', () => {
    renderPalette();
    // 기능형(일반 키오스크)과 실물 모델은 라벨이 갈린다. 같은 이름이 둘 뜨면 사용자가 구분 못 한다
    expect(screen.getAllByText('설문 키오스크')).toHaveLength(1);
    expect(screen.getAllByText(/SURVEY/)).toHaveLength(1);
  });

  it('manifest 가 비면 장식은 하드코딩 목록으로 떨어지고 기능형은 그대로다', () => {
    renderPalette({ assets: [] });
    expect(thumb('AI 직원')).toBeTruthy();
    expect(thumb('기본 패널')).toBeTruthy();
    expect(screen.queryByLabelText('에셋 검색')).toBeNull();
  });
});

describe('상한과 검색', () => {
  it('상한에 닿으면 기능형도 비활성 — 눌러도 안 되는 것을 눌리게 두지 않는다', () => {
    renderPalette({ currentCount: 12 });
    expect(thumb('AI 직원').disabled).toBe(true);
  });

  it('검색이 기능형에도 걸린다 — 검색하면 AI 직원이 사라지는 일이 없게', () => {
    renderPalette();
    fireEvent.change(screen.getByLabelText('에셋 검색'), { target: { value: 'AI' } });
    expect(thumb('AI 직원')).toBeTruthy();
    expect(screen.queryByText('노트북')).toBeNull();
  });
});
