// @vitest-environment jsdom
// 조작 안내 목록 — FE 임베드에서 이 목록이 **유일한** 조작 안내다(Unity 카드는 -456 으로
// 숨겨졌다). 그래서 snapshot 을 찍지 않고 "사용자가 실제로 보는 조작 항목"을 검증한다.
// 2026-09-16 에 HUD 상시 카드를 걷고 ESC 메뉴 오버레이로 옮겼다 — 목록 자체는 그대로다.
//
// 설명은 **한 단어**로 끝낸다 (S15P21A604-631). 우클릭 시야조작은 -631 에서 "손에 익어 안 읽힌다"며
// 뺐던 항목이나, S15P21A604-798 에서 요청자 확인 하에 결정을 번복해 다시 넣는다.
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { ControlGuideList } from '../../ControlGuideList';
import { __resetWorldContextForTests, applyBoothContext } from '../../../model/worldContext';

afterEach(() => {
  cleanup();
  __resetWorldContextForTests();
});

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
  it('사용자가 실제로 묻는 기본 7종이 있다 (우클릭 포함)', () => {
    render(<ControlGuideList />);
    expect(hasControl(['W', 'A', 'S', 'D'], '이동')).toBe(true);
    expect(hasControl(['Shift'], '달리기')).toBe(true);
    expect(hasControl(['Space'], '점프')).toBe(true);
    expect(hasControl(['F'], '상호작용')).toBe(true);
    expect(hasControl(['Alt', '클릭'], '감정')).toBe(true);
    expect(hasControl(['우클릭'], '시야')).toBe(true);
    expect(hasControl(['Esc'], '메뉴')).toBe(true);
  });

  it('동작하지 않는 H 안내를 두지 않는다 — FE 임베드에서 아무 일도 일어나지 않는다', () => {
    render(<ControlGuideList />);
    const badges = Array.from(document.querySelectorAll('.world-key')).map((k) => k.textContent?.trim());
    expect(badges).not.toContain('H');
  });
});

describe('tab 미니맵 안내 — 축제장(부스 밖)일 때만', () => {
  it('부스 밖이면 tab 안내가 보인다', () => {
    render(<ControlGuideList />);
    expect(hasControl(['Tab'], '미니맵')).toBe(true);
  });

  it('부스 안이면 tab 안내가 사라진다 — 미니맵이 의미 없는 자리다', () => {
    applyBoothContext(true, 1);
    render(<ControlGuideList />);
    expect(hasControl(['Tab'], '미니맵')).toBe(false);
    expect(descriptions()).toHaveLength(7);
  });
});

describe('설명 길이', () => {
  it('설명이 한 단어다 — 이 조건이 깨지면 카드가 다시 복잡해진다', () => {
    render(<ControlGuideList />);
    const found = descriptions();
    // 기본 7종 + 부스 밖에서만 보이는 Tab 미니맵
    expect(found.length).toBe(8);
    for (const text of found) {
      expect(text).not.toBe('');
      expect(text.includes(' ')).toBe(false);
    }
  });

  it('개발 환경 사정을 사용자에게 말하지 않는다 — 목업 안내 문구가 없다', () => {
    render(<ControlGuideList />);
    expect(screen.queryByText(/목업/)).toBeNull();
  });
});
