// @vitest-environment jsdom
// 인스펙터의 연결 입력칸 노출 규칙 (S15P21A604-811, GitLab #194).
//
// `linksConfigId` 한 플래그가 입력칸 유무를 정한다. PROJECT_PANEL 을 내리면서 그 자리가
// 마지막 else 로 떨어지는데, 거기 문구가 LAPTOP 전용이었다 — 타입별로 갈랐고 그것을 잠근다.
// 입력칸이 남아 있으면 사용자가 숫자를 넣고, 그 값은 서버가 더는 보지 않는다.
import { describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach } from 'vitest';
import { PropertiesPanel } from '../../ui/PropertiesPanel';
import type { LayoutObject } from '../../../../entities/layout/types';

afterEach(cleanup);

function show(type: LayoutObject['type']) {
  const object: LayoutObject = { objectId: 'o1', type, position: { x: 0, y: 0, z: 0 }, rotationY: 0 };
  render(
    <PropertiesPanel
      object={object}
      bounds={{ width: 9.4, depth: 6 }}
      onMove={vi.fn()}
      onRotate={vi.fn()}
      onLinkContent={vi.fn()}
      onSetAssetCode={vi.fn()}
      onRemove={vi.fn()}
    />,
  );
}

describe('PROJECT_PANEL — configId 를 쓰지 않는다', () => {
  it('연결 콘텐츠 ID 입력칸이 없다', () => {
    show('PROJECT_PANEL');
    expect(screen.queryByLabelText('연결 콘텐츠 ID')).toBeNull();
  });

  it('어디서 등록하는지 안내한다 — LAPTOP 문구가 재사용되면 안 된다', () => {
    show('PROJECT_PANEL');
    expect(screen.getByText(/전시 프로젝트는 부스 관리/)).toBeTruthy();
    expect(screen.queryByText(/홈페이지 주소는 부스 설정/)).toBeNull();
  });
});

describe('경계 회귀', () => {
  it('LAPTOP 안내문은 그대로다', () => {
    show('LAPTOP');
    expect(screen.getByText(/홈페이지 주소는 부스 설정/)).toBeTruthy();
  });

  it('SURVEY_KIOSK 는 여전히 연결 입력칸을 갖는다 — 한꺼번에 내려가지 않았는지 본다', () => {
    show('SURVEY_KIOSK');
    expect(screen.getByLabelText('연결 콘텐츠 ID')).toBeTruthy();
  });
});

