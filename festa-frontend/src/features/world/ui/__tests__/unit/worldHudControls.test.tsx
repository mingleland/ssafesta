// @vitest-environment jsdom
// WorldHud 조작 안내 — FE 임베드에서 이 목록이 **유일한** 조작 안내다(Unity 카드는 -456 으로
// 숨겨졌다). 그래서 snapshot 을 찍지 않고 "사용자가 실제로 보는 조작 항목"을 검증한다.
//
// 설명은 **한 단어**로 끝낸다 (S15P21A604-631). 이 카드는 키를 처음 익힐 때 훑는 것이지 읽는
// 문서가 아니다 — 설명이 길어지면 훑기가 느려지고, 그래서 사용자가 "너무 복잡하다"고 보고했다.
// 여기서 잠그는 것은 **어떤 조작이 있는가** 와 **설명이 짧게 유지되는가** 둘이다.
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

/** 키 배지를 뺀 설명만 — 길이를 재려면 배지 글자가 섞이면 안 된다 */
function descriptions(): string[] {
  return Array.from(document.querySelectorAll('.world-hud-keys li')).map((li) => {
    const clone = li.cloneNode(true) as HTMLElement;
    clone.querySelectorAll('.world-key').forEach((k) => k.remove());
    return (clone.textContent ?? '').trim();
  });
}

describe('조작 항목', () => {
  it('사용자가 실제로 묻는 6종이 있다', () => {
    render(<WorldHud />);
    expect(hasControl(['W', 'A', 'S', 'D'], '이동')).toBe(true);
    expect(hasControl(['Shift'], '달리기')).toBe(true);
    expect(hasControl(['Space'], '점프')).toBe(true);
    expect(hasControl(['F'], '상호작용')).toBe(true);
    expect(hasControl(['Alt', '클릭'], '감정')).toBe(true);
    expect(hasControl(['Esc'], '메뉴')).toBe(true);
  });

  it('동작하지 않는 H 안내를 두지 않는다 — FE 임베드에서 아무 일도 일어나지 않는다', () => {
    render(<WorldHud />);
    const badges = Array.from(document.querySelectorAll('.world-key')).map((k) => k.textContent?.trim());
    expect(badges).not.toContain('H');
  });

  it('시야 조작은 목록에 두지 않는다 — 손에 익는 것이라 적어 두어도 읽히지 않는다', () => {
    render(<WorldHud />);
    const badges = Array.from(document.querySelectorAll('.world-key')).map((k) => k.textContent?.trim());
    expect(badges).not.toContain('마우스 우클릭');
  });
});

describe('설명 길이', () => {
  it('설명이 한 단어다 — 이 조건이 깨지면 카드가 다시 복잡해진다', () => {
    render(<WorldHud />);
    const found = descriptions();
    expect(found.length).toBe(6);
    for (const text of found) {
      expect(text).not.toBe('');
      // 공백이 없다 = 한 단어. "부스 입장·나가기 · 가까운 오브젝트와 상호작용" 같은 설명이 다시
      // 들어오면 여기서 터진다
      expect(text.includes(' ')).toBe(false);
    }
  });

  it('개발 환경 사정을 사용자에게 말하지 않는다 — 목업 안내 문구가 없다', () => {
    render(<WorldHud />);
    expect(screen.queryByText(/목업/)).toBeNull();
  });
});

describe('접기·펼치기', () => {
  it('그대로 동작한다', () => {
    render(<WorldHud />);

    expect(screen.getByLabelText('조작 안내')).toBeTruthy();
    fireEvent.click(screen.getByLabelText('조작 안내 닫기'));
    expect(screen.queryByLabelText('조작 안내')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '조작 안내' }));
    expect(screen.getByLabelText('조작 안내')).toBeTruthy();
    expect(hasControl(['Shift'], '달리기')).toBe(true); // 다시 열어도 목록이 온전하다
  });
});
