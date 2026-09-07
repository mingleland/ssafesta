// @vitest-environment jsdom
// WorldHud 조작 안내 (S15P21A604-460, GitLab #132) — FE 임베드에서 이 목록이 **유일한** 조작 안내다.
// Unity 카드는 -456 으로 숨겨졌으므로, 여기 빠진 조작은 사용자가 알 길이 없다.
//
// snapshot 을 찍지 않는다 — 마크업이 아니라 "사용자가 실제로 보는 조작 항목"을 검증한다.
// 항목 하나가 사라지면 여기서 터져야 한다.
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { WorldHud } from '../../WorldHud';

afterEach(cleanup);

/** 조작 안내 목록에서 키 배지와 설명이 같은 항목(li)에 있는지 본다 */
function hasControl(keys: string[], description: string): boolean {
  const items = Array.from(document.querySelectorAll('.world-hud-keys li'));
  return items.some((li) => {
    const badges = Array.from(li.querySelectorAll('.world-key')).map((k) => k.textContent?.trim());
    const text = li.textContent ?? '';
    return keys.every((k) => badges.includes(k)) && text.includes(description);
  });
}

describe('WorldHud 조작 안내 (-460)', () => {
  it('사용자 테스트에서 묻는 조작 4종이 새로 들어간다', () => {
    render(<WorldHud />);
    expect(hasControl(['Shift'], '달리기')).toBe(true);
    expect(hasControl(['Space'], '점프')).toBe(true);
    expect(hasControl(['마우스 우클릭'], '시야 돌리기')).toBe(true);
    expect(hasControl(['Alt', '클릭'], '감정 표현')).toBe(true);
  });

  it('기존 3종이 그대로 남는다 — 추가가 기존을 밀어내지 않았다', () => {
    render(<WorldHud />);
    expect(hasControl(['W', 'A', 'S', 'D'], '이동')).toBe(true);
    expect(hasControl(['F'], '상호작용')).toBe(true);
    expect(hasControl(['Esc'], '열린 창 닫기')).toBe(true);
  });

  it('동작하지 않는 H 안내를 두지 않는다 — FE 임베드에서 아무 일도 일어나지 않는다', () => {
    render(<WorldHud />);
    const badges = Array.from(document.querySelectorAll('.world-key')).map((k) => k.textContent?.trim());
    expect(badges).not.toContain('H');
  });

  it('접기·펼치기가 그대로 동작한다', () => {
    render(<WorldHud />);

    expect(screen.getByLabelText('조작 안내')).toBeTruthy();
    fireEvent.click(screen.getByLabelText('조작 안내 닫기'));
    expect(screen.queryByLabelText('조작 안내')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '조작 안내' }));
    expect(screen.getByLabelText('조작 안내')).toBeTruthy();
    expect(hasControl(['Shift'], '달리기')).toBe(true); // 다시 열어도 목록이 온전하다
  });

  it('mock 월드 안내 문구가 그대로다', () => {
    render(<WorldHud mock />);
    expect(screen.getByText(/월드는 목업 정지 화면입니다/)).toBeTruthy();
  });

  it('실 월드에서는 mock 문구를 띄우지 않는다', () => {
    render(<WorldHud />);
    expect(screen.queryByText(/월드는 목업 정지 화면입니다/)).toBeNull();
  });
});
