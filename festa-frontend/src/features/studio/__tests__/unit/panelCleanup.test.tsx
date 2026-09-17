// @vitest-environment jsdom
// Booth Studio 패널의 상시 비활성 컨트롤 (S15P21A604-616 · 617).
//
// 누를 수 없는 버튼이 상시 떠 있으면 화면이 무엇을 할 수 있는지 말해 주지 못한다 — 눌러 본
// 뒤에야 안 된다는 것을 안다. 그래서 재는 것은 개별 버튼의 유무가 아니라 **상시 disabled 가
// 다시 늘지 않는가** 다. 새 목업 컨트롤이 들어오면 개수 단언이 먼저 깨진다.
//
// 예외는 구조 모드의 잠긴 자산뿐이다. 그건 목업이 아니라 보유하면 열리는 항목이고, 왜 못
// 누르는지 title 이 말한다 — 그 경계도 함께 박아 둔다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { LayoutObject } from '../../../../entities/layout/types';
import { AssetPalette } from '../../ui/shell/AssetPalette';
import { PropertiesPanel } from '../../ui/PropertiesPanel';
import { TopToolbar } from '../../ui/shell/TopToolbar';
import { TransformBar } from '../../ui/shell/TransformBar';
import { TEMPLATE_PRESETS } from '../../model/visualAssets';

afterEach(cleanup);

/** 지금 화면에서 누를 수 없는 컨트롤 — 조건부 disabled 는 아래 렌더에서 전부 열어 둔다 */
function deadControls(): string[] {
  return Array.from(document.querySelectorAll('button:disabled')).map(
    (el) => el.getAttribute('aria-label') ?? el.textContent?.trim() ?? '(이름 없음)',
  );
}

function object(): LayoutObject {
  return { objectId: 'a', type: 'DECORATION', position: { x: 1, y: 0, z: 1 }, rotationY: 0 };
}

function renderPalette(mode: 'layout' | 'facade' | 'template') {
  return render(
    <AssetPalette
      mode={mode}
      currentCount={0}
      maxObjects={50}
      catalog={[]}
      assets={[]}
      activePresetId={null}
      onAddObject={vi.fn()}
      onAddAsset={vi.fn()}
      onApplyTemplate={vi.fn()}
      onPickPreset={vi.fn()}
    />,
  );
}

describe('상단 툴바', () => {
  it('할 수 있는 일이 전부 열려 있으면 누를 수 없는 버튼이 없다', () => {
    render(
      <TopToolbar
        boothName="테스트 부스"
        saveStatus="dirty"
        dirty
        conflict={false}
        leaseExpired={false}
        canSave
        canPublish
        publishing={false}
        publishedVersion={null}
        zoomPercent={100}
        canUndo
        canRedo
        canReset
        onBack={vi.fn()}
        onSave={vi.fn()}
        onPublish={vi.fn()}
        onZoomToggle={vi.fn()}
        onUndo={vi.fn()}
        onRedo={vi.fn()}
        onReset={vi.fn()}
      />,
    );
    expect(deadControls()).toEqual([]);
  });
});

describe('변형 툴바', () => {
  it('누를 수 없는 버튼이 없다 — 스냅 토글은 동작하고 옵션 패널은 없다', () => {
    render(<TransformBar tool="select" snap onTool={vi.fn()} onSnapToggle={vi.fn()} onFrame={vi.fn()} />);
    expect(deadControls()).toEqual([]);
    expect(screen.getByRole('button', { name: '스냅' })).toBeTruthy();
  });
});

describe('에셋 팔레트', () => {
  it('구조 모드에서 못 누르는 것은 잠긴 자산뿐이다 — 이유를 화면이 말한다', async () => {
    renderPalette('layout');
    // 잠금은 목업이 아니다. 보유하면 열리고, 그때까지 왜 못 누르는지 화면이 말한다.
    // 그 말은 2026-09-17 부터 공통 Tooltip 이 한다 — 비활성 버튼은 앵커가 hover 를 받는다.
    for (const button of document.querySelectorAll('button:disabled')) {
      const anchor = button.closest('.festa-tooltip-anchor');
      expect(anchor, '비활성 버튼에는 설명을 받을 앵커가 있어야 한다').not.toBeNull();
      fireEvent.mouseEnter(anchor as Element);
      expect((await screen.findByRole('tooltip')).textContent).toContain('잠금');
      fireEvent.mouseLeave(anchor as Element);
    }
  });

  it('구조 모드에 "에셋 추가"가 없다 — 업로드는 계약이 오면 그때 넣는다', () => {
    renderPalette('layout');
    expect(screen.queryByText('에셋 추가')).toBeNull();
  });

  it('외관 모드에 누를 수 없는 버튼이 없다 — 고를 것을 두지 않고 저장 자리를 가리킨다', () => {
    renderPalette('facade');
    expect(deadControls()).toEqual([]);
    expect(screen.getByText(/부스 외관/)).toBeTruthy();
  });

  it('템플릿 모드에 누를 수 없는 버튼이 없다', () => {
    renderPalette('template');
    expect(deadControls()).toEqual([]);
  });
});

describe('인스펙터', () => {
  function renderInspector() {
    return render(
      <PropertiesPanel
        object={object()}
        bounds={{ width: 10, depth: 10 }}
        onMove={vi.fn()}
        onRotate={vi.fn()}
        onLinkContent={vi.fn()}
        onSetAssetCode={vi.fn()}
        onRemove={vi.fn()}
      />,
    );
  }

  it('누를 수 없는 버튼이 없다', () => {
    renderInspector();
    expect(deadControls()).toEqual([]);
  });

  it('저장되지 않는 값을 편집 가능한 것처럼 보여 주지 않는다', () => {
    renderInspector();
    for (const label of ['양면', '그림자', '재질', '복제']) {
      expect(screen.queryByText(label)).toBeNull();
    }
    expect(document.querySelector('.studio-provisional')).toBeNull();
  });

  it('실제로 저장되는 편집은 그대로 남는다 — 위치·회전·삭제', () => {
    renderInspector();
    expect(screen.getByLabelText('x')).toBeTruthy();
    expect(screen.getByLabelText('회전')).toBeTruthy();
    expect(screen.getByRole('button', { name: /삭제/ })).toBeTruthy();
  });
});

describe('템플릿 프리셋', () => {
  it('캔버스 미리보기 입력은 남아 있다 — 프리셋을 고르면 바닥·대표색이 바뀐다', () => {
    expect(TEMPLATE_PRESETS.length).toBeGreaterThan(0);
    for (const preset of TEMPLATE_PRESETS) {
      expect(preset.floorHex).toMatch(/^#[0-9a-fA-F]{6}$/);
      expect(preset.primaryHex).toMatch(/^#[0-9a-fA-F]{6}$/);
    }
  });

  it('화면에 보여만 주고 아무 데도 쓰이지 않는 색은 없다', () => {
    // accentHex 는 저장도 안 되고 캔버스 decor 에도 들어가지 않았다 — 표시 전용 색이었다
    for (const preset of TEMPLATE_PRESETS) {
      expect('accentHex' in preset).toBe(false);
    }
  });
});
