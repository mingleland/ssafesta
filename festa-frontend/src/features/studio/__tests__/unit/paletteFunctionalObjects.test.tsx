// @vitest-environment jsdom
// 팔레트의 기능형 노출 (GitLab #201, S15P21A604-783).
//
// 재는 것은 "실물 자산이 있을 때 기능 오브젝트가 남아 있는가" 하나다. 예전 팔레트는
// 실자산 유무로 목록 전체를 배타 분기해서, manifest 에 의자 하나만 들어와도 AI 직원부터
// 채용 보드까지 8종이 통째로 사라졌다. 그 구조로 돌아가면 이 스위트가 먼저 깨진다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { AssetPalette } from '../../ui/shell/AssetPalette';
import { DECOR_PALETTE, FUNCTIONAL_PALETTE } from '../../model/visualAssets';
import type { BoothAssetEntry } from '../../model/boothAssetManifest';

afterEach(cleanup);

const asset = (assetCode: string, objectType: BoothAssetEntry['objectType']): BoothAssetEntry => ({
  assetCode,
  objectType,
  typeDefault: false,
  url: `${assetCode}.glb`,
  thumbnail: null,
  bytes: 1000,
  triangles: 100,
  bounds: { min: [0, 0, 0], max: [1, 1, 1] },
  source: { fbx: 'x.fbx', unitScale: 1, upAxis: 'yUp', rawBounds: { min: [0, 0, 0], max: [1, 1, 1] } },
});

function palette(assets: BoothAssetEntry[]) {
  return render(
    <AssetPalette
      mode="layout"
      currentCount={0}
      maxObjects={12}
      catalog={[]}
      assets={assets}
      activePresetId={null}
      onAddObject={vi.fn()}
      onAddAsset={vi.fn()}
      onApplyTemplate={vi.fn()}
      onPickPreset={vi.fn()}
    />,
  );
}

const FUNCTIONAL_TYPES = [
  'AI_AGENT',
  'PROJECT_PANEL',
  'SURVEY_KIOSK',
  'CONSULTATION_DESK',
  'VIDEO_SCREEN',
  'LIKE_VOTE',
  'RECRUITMENT_BOARD',
  'LAPTOP',
];

describe('기능형 / 장식형 가르기', () => {
  it('기능형 목록은 계약 기능 타입 8종을 정확히 덮는다', () => {
    expect(FUNCTIONAL_PALETTE.items.map((i) => i.objectType).sort()).toEqual([...FUNCTIONAL_TYPES].sort());
  });

  it('기능형에는 assetCode 가 없고 장식형에는 있다 — 가르는 기준이 흐려지지 않는다', () => {
    expect(FUNCTIONAL_PALETTE.items.every((i) => i.assetCode === undefined)).toBe(true);
    expect(DECOR_PALETTE.flatMap((s) => s.items).every((i) => i.assetCode !== undefined)).toBe(true);
  });
});

describe('AssetPalette 구조 모드', () => {
  // 조회는 화면에 보이는 라벨로 한다 — 2026-09-17 에 native title 을 공통 Tooltip 으로 옮기면서
  // title 이 사라졌다. 팔레트 칸은 원래 라벨을 글자로도 보여 주고 있었다.
  const thumb = (label: string | RegExp) => screen.getByText(label, { selector: '.studio-thumb-label' });
  const noThumb = (label: string | RegExp) => screen.queryByText(label, { selector: '.studio-thumb-label' });

  it('실물 자산이 있어도 기능 오브젝트가 전부 남는다 (#201 회귀)', () => {
    palette([asset('FURN_CHAIR_01_WHITE', 'FURNITURE'), asset('STRUCT_PANEL_01', 'DECORATION')]);
    for (const item of FUNCTIONAL_PALETTE.items) {
      expect(thumb(item.label)).toBeTruthy();
    }
  });

  it('실물 자산이 있으면 실물도 함께 보인다 — 한쪽이 다른 쪽을 밀어내지 않는다', () => {
    palette([asset('FURN_CHAIR_01_WHITE', 'FURNITURE')]);
    expect(thumb('AI 직원')).toBeTruthy();
    expect(thumb(/의자 01 WHITE/)).toBeTruthy();
  });

  it('같은 objectType 이라도 기능형과 실물 variant 가 공존한다 — SURVEY_KIOSK', () => {
    palette([asset('BOOTH_KIOSK_SURVEY', 'SURVEY_KIOSK')]);
    expect(thumb('설문 키오스크')).toBeTruthy();
    expect(thumb(/전자기기 SURVEY/)).toBeTruthy();
  });

  it('manifest 가 비면 장식형 폴백이 기능형과 함께 보인다', () => {
    palette([]);
    expect(thumb('AI 직원')).toBeTruthy();
    expect(thumb('기본 패널')).toBeTruthy();
  });

  it('검색은 기능형과 실물형 둘 다를 거른다', () => {
    palette([asset('FURN_CHAIR_01_WHITE', 'FURNITURE')]);
    fireEvent.change(screen.getByLabelText('에셋 검색'), { target: { value: 'AI 직원' } });
    expect(thumb('AI 직원')).toBeTruthy();
    expect(noThumb(/의자 01 WHITE/)).toBeNull();

    fireEvent.change(screen.getByLabelText('에셋 검색'), { target: { value: '의자' } });
    expect(thumb(/의자 01 WHITE/)).toBeTruthy();
    expect(noThumb('AI 직원')).toBeNull();
  });
});
