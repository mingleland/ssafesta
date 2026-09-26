// @vitest-environment jsdom
// AI Chat UI의 게스트 차단을 검증한다(S15P21A604-118). 회원 대화 왕복·Conversation 상태 모델
// (진행중/오류/종료)은 실서버 결선(S15P21A604-189) 쪽 AiChatOverlay.test.tsx가 이미 real API
// mock으로 촘촘히 덮는다 — 여기서 옛 mock 응답 텍스트로 다시 검증하면 중복이자 실서버 전환 후
// 그 텍스트가 더 이상 나오지 않아 깨진다(실측). 게스트 진입점 차단은 그 파일이 다루지 않는
// 이 브랜치만의 순증 커버리지라 여기 남긴다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AiChatOverlay } from '../../AiChatOverlay';
import { closeOverlay } from '../../../../../shared/types/overlay';
import {
  __resetSessionForTests,
  setGuestSession,
  setMemberSession,
} from '../../../../auth/model/session';

const FUTURE = new Date(Date.now() + 60_000).toISOString();

HTMLElement.prototype.scrollTo = vi.fn();

afterEach(() => {
  closeOverlay();
  cleanup();
  __resetSessionForTests();
  sessionStorage.clear();
});

function renderAt(path = '/app/world') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/app/world" element={<AiChatOverlay payload={{ boothId: 1 }} />} />
        <Route path="/login" element={<p>로그인 화면</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('AiChatOverlay 회원 접근 UI', () => {
  it('게스트에게는 로그인 유도만 표시하고 대화 진입점을 숨긴다', () => {
    setGuestSession('at', FUTURE);
    renderAt();

    expect(screen.queryByText('로그인이 필요합니다')).not.toBeNull();
    expect(screen.queryByText('로그인하러 가기')).not.toBeNull();
    expect(screen.queryByPlaceholderText('부스에 대해 물어보세요')).toBeNull();
    expect(screen.queryByText('기술 스택')).toBeNull();
  });

  it('로그인 버튼은 현재 경로를 저장하고 로그인 화면으로 이동한다', () => {
    setGuestSession('at', FUTURE);
    renderAt();

    fireEvent.click(screen.getByText('로그인하러 가기'));

    expect(screen.queryByText('로그인 화면')).not.toBeNull();
    expect(sessionStorage.getItem('festa-auth-return-to')).toBe('/app/world');
  });

  it('member는 게스트 차단 뷰 없이 메시지 입력·전송 UI를 그대로 본다 (회귀)', () => {
    setMemberSession('at', FUTURE);
    renderAt();

    expect(screen.queryByText('로그인이 필요합니다')).toBeNull();
    expect(screen.queryByPlaceholderText('부스에 대해 물어보세요')).not.toBeNull();
  });
});
