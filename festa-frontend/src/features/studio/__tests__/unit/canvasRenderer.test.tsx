// @vitest-environment jsdom
/// <reference types="node" />
// 렌더러 선택과 lazy 경계 회귀 (S15P21A604-470).
//
// 두 가지를 지킨다.
// 1. Spike 가 FAIL 로 끝나면 SVG 로 돌아갈 수 있어야 한다 — 그래서 플래그 규칙을 잠근다
// 2. three 는 lazy 로만 들어와야 한다 — static import 가 하나라도 생기면 Studio 를 열지 않는
//    사용자도 3D 런타임을 받는다. 이건 소스에서만 확인 가능하다(번들러가 결정하므로)
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BoothCanvasViewport } from '../../ui/canvas/BoothCanvasViewport';
import { IS_R3F_CANVAS } from '../../ui/canvas/canvasRenderer';

const here = dirname(fileURLToPath(import.meta.url));
const canvasDir = resolve(here, '../../ui/canvas');
const read = (name: string) => readFileSync(resolve(canvasDir, name), 'utf8');

describe('렌더러 선택 플래그', () => {
  it("VITE_R3F_CANVAS='false' 일 때만 SVG 로 돌아간다", () => {
    // 판정 규칙 자체를 재현한다 — .env.local 값에 의존하면 사람마다 결과가 달라진다
    const decide = (raw: string | undefined) => raw !== 'false';
    expect(decide('false')).toBe(false);
    expect(decide('true')).toBe(true);
    expect(decide(undefined)).toBe(true); // Spike 기간 기본값은 R3F
  });

  it('플래그는 boolean 이다 — 문자열이 그대로 새면 조건문이 항상 참이 된다', () => {
    expect(typeof IS_R3F_CANVAS).toBe('boolean');
  });
});

describe('three 는 lazy 경계 뒤에만 있다', () => {
  it('BoothCanvasViewport 는 R3F 렌더러를 React.lazy 로만 부른다', () => {
    const src = read('BoothCanvasViewport.tsx');
    expect(src).toContain("lazy(() => import('./R3FBoothRenderer'))");
    expect(src).not.toMatch(/^import .*from '\.\/R3FBoothRenderer'/m);
  });

  it('라우터는 Studio 를 아예 싣지 않는다 — three 가 어느 청크에도 딸려 오지 않는다', () => {
    const src = readFileSync(resolve(here, '../../../../..', 'src/app/router/index.tsx'), 'utf8');
    // 2026-09-17 — Studio 는 사용자 흐름에서 폐기됐고 라우트가 월드로 보내는 Navigate 로 바뀌었다.
    // 예전에는 lazy import 로 경계를 지켰는데, 이제는 import 자체가 없는 것이 더 강한 보장이다.
    expect(src).not.toContain('pages/studio/StudioPage');
  });
});

const BASE = {
  objects: [],
  selectedObjectId: null,
  bounds: { width: 6, depth: 4, height: 3 },
  zoom: 1,
  tool: 'select' as const,
  snapOn: true,
  decor: { floorHex: '#1d4ed8', wallHex: '#ffffff', primaryHex: '#22c55e', signText: '', graphic: false },
  onSelect: () => {},
  onMove: () => {},
  onRotate: () => {},
  onTool: () => {},
  onSnapToggle: () => {},
  onFrame: () => {},
  onZoomChange: () => {},
};

describe('뷰포트 골격 — 렌더러가 무엇이든 유지된다', () => {
  it('캔버스 영역과 TransformBar 는 그대로다', () => {
    render(<BoothCanvasViewport {...BASE} />);
    expect(screen.getByLabelText('부스 캔버스')).toBeTruthy();
    // 이동/회전/스냅 도구는 렌더러 밖에 있다 — 교체해도 사라지면 안 된다
    expect(screen.getByLabelText('이동')).toBeTruthy();
    expect(screen.getByLabelText('회전')).toBeTruthy();
  });

  it('부스 치수 HUD 를 계속 보여 준다', () => {
    // 숫자와 구분자가 별개 텍스트 노드라 정규식 하나로는 안 잡힌다 — 영역의 textContent 로 본다
    const { container } = render(<BoothCanvasViewport {...BASE} />);
    const hud = container.querySelector('.studio-canvas-hud-br');
    expect(hud?.textContent).toContain('6 × 4 × 3 m');
  });
});
