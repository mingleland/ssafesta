// @vitest-environment jsdom
// S15P21A604-186 완료 조건 ④ — 강제 오류 fixture로 게임이 실패한 뒤 "나가기"를 누르면
// OverlayHost가 완전히 걷히고 FESTA 나머지 화면(형제 엘리먼트로 흉내)은 그대로 남아 있는지
// 검증한다. GameOverlay를 직접 렌더하지 않고 OverlayHost를 통해 실제 배선 그대로 확인한다 —
// overlayHostGameWiring.test.tsx(S15P21A604-114)와 같은 원칙: 배선이 빠져 있으면 컴포넌트
// 단위 테스트만으로는 못 잡는다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { OverlayHost } from '../../OverlayHost';
import { closeOverlay, openOverlay } from '../../../../shared/types/overlay';

const BOOTH_ID = 3;
const CONFIG_ID = 7;
const GAME_ID = 42;
const PORTAL_PATH = `/api/v1/game-portals/${CONFIG_ID}`;
const PUBLISHED_PATH = `/api/v1/games/${GAME_ID}/published`;

const jsonResponse = (body: unknown, status = 200): Response => new Response(
  JSON.stringify(body),
  { status, headers: { 'Content-Type': 'application/json' } },
);

// 포털은 정상 연결되지만, 게시된 프로젝트 자체가 손상된 schema 오류 fixture다 — -186의
// "render/runtime/asset/schema 오류 fixture" 4종 중 schema 계열에 해당한다.
const installFetch = () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes(PORTAL_PATH)) {
      return jsonResponse({
        configId: CONFIG_ID,
        boothId: BOOTH_ID,
        playable: true,
        gameId: GAME_ID,
        publishedVersion: 1,
        unavailableReason: null,
      });
    }
    if (url.includes(PUBLISHED_PATH)) {
      return jsonResponse({ code: 'GAME_PROJECT_INVALID', message: '손상됨', errors: [], warnings: [] }, 422);
    }
    return jsonResponse({ code: 'UNKNOWN', message: 'unexpected', errors: [], warnings: [] }, 404);
  }));
};

afterEach(() => {
  closeOverlay();
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('GameOverlay — 오류 후 닫기 (S15P21A604-186 조건 ④)', () => {
  it('강제 오류 fixture로 실패한 뒤 나가기를 누르면 오버레이가 완전히 걷히고 나머지 화면은 그대로다', async () => {
    installFetch();
    openOverlay('GAME', { boothId: BOOTH_ID, objectId: 'portal-1', configId: CONFIG_ID });

    render(
      <div>
        <span>FESTA 월드 화면</span>
        <OverlayHost />
      </div>,
    );

    // 손상된 프로젝트 오류 화면까지 도달했는지 먼저 확인 — 조건 ①(격리)이 실패 화면 자체는
    // 그리게 두는 것과 같은 전제다.
    expect(await screen.findByText('게시된 게임 데이터가 올바르지 않습니다.')).not.toBeNull();
    expect(screen.queryByText('FESTA 월드 화면')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '나가기' }));

    // 오버레이 자체(다이얼로그)가 DOM에서 완전히 사라져야 한다 — 에러 문구만 사라지는 것으로는
    // 부족하다. OverlayHost가 걷혔다는 것이 "기존 앱으로 정상 복귀"의 최소 조건이다.
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'FESTA 게임' })).toBeNull();
    });
    expect(screen.queryByText('FESTA 월드 화면')).not.toBeNull();
  });
});
