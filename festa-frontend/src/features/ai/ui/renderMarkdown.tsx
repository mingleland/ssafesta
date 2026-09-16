// AI 답변을 안전하게 마크다운처럼 보여준다 — 모델 출력은 비신뢰 데이터라
// dangerouslySetInnerHTML 로 HTML 문자열을 주입하지 않는다. 텍스트를 React 엘리먼트
// 트리로만 바꾼다: 삽입되는 것은 항상 React 텍스트 노드(자동 이스케이프)뿐이고, HTML
// 문자열이 만들어지는 지점이 없다.
// ponytail: 지원 범위는 굵게/기울임/인라인 코드/글머리·번호 목록뿐이다. 표·코드펜스·링크가
// 필요해지면 그때 라이브러리(react-markdown 등)로 옮긴다.
import type { ReactNode } from 'react';

const INLINE_PATTERN = /\*\*([^*]+)\*\*|`([^`]+)`|\*([^*]+)\*/g;

function renderInline(text: string, keyPrefix: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let matchCount = 0;
  INLINE_PATTERN.lastIndex = 0;
  while ((match = INLINE_PATTERN.exec(text)) !== null) {
    if (match.index > lastIndex) nodes.push(text.slice(lastIndex, match.index));
    const key = `${keyPrefix}-${matchCount}`;
    if (match[1] !== undefined) nodes.push(<strong key={key}>{match[1]}</strong>);
    else if (match[2] !== undefined) nodes.push(<code key={key}>{match[2]}</code>);
    else if (match[3] !== undefined) nodes.push(<em key={key}>{match[3]}</em>);
    lastIndex = INLINE_PATTERN.lastIndex;
    matchCount += 1;
  }
  if (lastIndex < text.length) nodes.push(text.slice(lastIndex));
  return nodes;
}

export function renderMarkdown(text: string): ReactNode {
  const lines = text.split('\n');
  const blocks: ReactNode[] = [];
  let listItems: string[] = [];
  let listType: 'ul' | 'ol' | null = null;

  function flushList(): void {
    if (listType === null) return;
    const items = listItems;
    const type = listType;
    const key = `list-${blocks.length}`;
    blocks.push(
      type === 'ul' ? (
        <ul key={key}>
          {items.map((item, idx) => (
            <li key={idx}>{renderInline(item, `${key}-${idx}`)}</li>
          ))}
        </ul>
      ) : (
        <ol key={key}>
          {items.map((item, idx) => (
            <li key={idx}>{renderInline(item, `${key}-${idx}`)}</li>
          ))}
        </ol>
      ),
    );
    listItems = [];
    listType = null;
  }

  for (const line of lines) {
    const bullet = /^[-*]\s+(.*)$/.exec(line);
    const numbered = /^\d+\.\s+(.*)$/.exec(line);
    if (bullet) {
      if (listType !== 'ul') flushList();
      listType = 'ul';
      listItems.push(bullet[1]);
      continue;
    }
    if (numbered) {
      if (listType !== 'ol') flushList();
      listType = 'ol';
      listItems.push(numbered[1]);
      continue;
    }
    flushList();
    if (line.trim() === '') continue;
    blocks.push(<p key={`p-${blocks.length}`}>{renderInline(line, `p-${blocks.length}`)}</p>);
  }
  flushList();
  return <>{blocks}</>;
}
