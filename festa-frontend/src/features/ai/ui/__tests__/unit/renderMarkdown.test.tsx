// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { renderMarkdown } from '../../renderMarkdown';

describe('renderMarkdown', () => {
  it('굵게·기울임·인라인 코드를 React 엘리먼트로 바꾼다', () => {
    const { container } = render(<>{renderMarkdown('**굵게** *기울임* `코드`')}</>);
    expect(container.querySelector('strong')?.textContent).toBe('굵게');
    expect(container.querySelector('em')?.textContent).toBe('기울임');
    expect(container.querySelector('code')?.textContent).toBe('코드');
  });

  it('- 로 시작하는 줄을 글머리 목록으로 묶는다', () => {
    const { container } = render(<>{renderMarkdown('- 첫째\n- 둘째')}</>);
    const items = container.querySelectorAll('ul > li');
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toBe('첫째');
    expect(items[1].textContent).toBe('둘째');
  });

  it('숫자. 로 시작하는 줄을 번호 목록으로 묶는다', () => {
    const { container } = render(<>{renderMarkdown('1. 하나\n2. 둘')}</>);
    expect(container.querySelectorAll('ol > li')).toHaveLength(2);
  });

  it('일반 문단 사이 빈 줄은 별도 문단으로 나눈다', () => {
    const { container } = render(<>{renderMarkdown('첫 문단\n\n둘째 문단')}</>);
    const paragraphs = container.querySelectorAll('p');
    expect(paragraphs).toHaveLength(2);
    expect(paragraphs[0].textContent).toBe('첫 문단');
    expect(paragraphs[1].textContent).toBe('둘째 문단');
  });

  it('HTML 문자열을 주입하지 않는다 — 태그처럼 보이는 텍스트도 그대로 이스케이프된다', () => {
    const { container } = render(<>{renderMarkdown('<img src=x onerror=alert(1)>')}</>);
    expect(container.querySelector('img')).toBeNull();
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>');
  });
});
