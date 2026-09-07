// @vitest-environment jsdom
// AI Chat UI의 게스트 차단과 회원 mock 대화 왕복을 검증한다(S15P21A604-118).
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
    expect(screen.queryByText('기술 스택이 궁금해요')).toBeNull();
  });

  it('로그인 버튼은 현재 경로를 저장하고 로그인 화면으로 이동한다', () => {
    setGuestSession('at', FUTURE);
    renderAt();

    fireEvent.click(screen.getByText('로그인하러 가기'));

    expect(screen.queryByText('로그인 화면')).not.toBeNull();
    expect(sessionStorage.getItem('festa-auth-return-to')).toBe('/app/world');
  });

  it('회원은 기존 메시지 입력·전송 UI로 mock 대화를 왕복한다', async () => {
    setMemberSession('at', FUTURE);
    renderAt();

    const input = screen.getByPlaceholderText('부스에 대해 물어보세요');
    fireEvent.change(input, { target: { value: '팀을 소개해 주세요' } });
    fireEvent.click(screen.getByRole('button', { name: '보내기' }));

    expect(await screen.findByText(/다섯 명이/)).not.toBeNull();
    expect(await screen.findByText('프로젝트_기획서.pdf')).not.toBeNull();
  });
});
