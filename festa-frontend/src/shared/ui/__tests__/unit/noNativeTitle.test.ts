/// <reference types="node" />
// 네이티브 `title` 잔존 검사 (2026-09-17).
//
// 공통 Tooltip 을 만들어도 다음 아이콘 버튼에 `title` 이 붙으면 화면마다 다른 말풍선이 다시 생긴다.
// 컴포넌트 prop 인 `title`(OverlayFrame·PageShell·Section…)과 가르려면 태그 이름을 봐야 하므로,
// `title=` 앞의 가장 가까운 여는 태그를 찾아 소문자(=DOM 요소)일 때만 센다.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    // 테스트는 사용자 화면이 아니다. 이 파일 자신이 규칙 문자열을 들고 있어 스스로 걸린다.
    if (entry.isDirectory() && entry.name === '__tests__') return [];
    if (entry.isDirectory()) return sources(path);
    return /\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

function nativeTitles(file: string): string[] {
  const source = readFileSync(file, 'utf8');
  const found: string[] = [];
  const pattern = /(?<![a-zA-Z])title=/g;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(source)) !== null) {
    let tag: string | null = null;
    for (let i = match.index; i >= 0; i -= 1) {
      if (source[i] !== '<') continue;
      const opening = /^<\/?([A-Za-z][\w.]*)/.exec(source.slice(i, i + 40));
      if (opening !== null) { tag = opening[1]; break; }
    }
    if (tag !== null && /^[a-z]/.test(tag)) {
      found.push(file.replace(/\\/g, '/') + ' <' + tag + '>');
    }
  }
  return found;
}

// Game Studio 는 타 파트 소유이고 자체 디자인 시스템(gss-*)을 쓴다 — 이 검사의 대상이 아니다.
// iframe 의 title 은 툴팁이 아니라 **필수 접근성 이름**이다. 브라우저도 말풍선을 띄우지 않는다.
const EXEMPT = [/^src\/game-studio\//, /<iframe>$/];

describe('네이티브 title 잔존', () => {
  it('사용자에게 보이는 title 은 공통 Tooltip 으로 대체돼 있다', () => {
    const remaining = sources('src')
      .flatMap(nativeTitles)
      .filter((entry) => !EXEMPT.some((rule) => rule.test(entry)));

    expect(remaining).toEqual([]);
  });
});
