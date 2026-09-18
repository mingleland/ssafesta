// PUBLISH_AI_AGENT_BINDING=false(기본) 에서 기존 게시본의 AI_AGENT 보존 (S15P21A604-898 머지 전 필수 확인).
// A. 게시본에 AI_AGENT 가 있다는 이유만으로 "변경사항 적용" 이 뜨지 않는다
// B. 다른 이유(legacy 가구)로 적용해도 AI_AGENT 가 지워지지 않는다
import { describe, expect, it, vi } from 'vitest';

vi.mock('../../model/boothManagementFlags', () => ({ SHOW_AI_ASSET_SECTION: false, PUBLISH_AI_AGENT_BINDING: false }));

import { compose, fromLayout, isUpToDate, toLayout } from '../../model/presentationComposer';
import type { LayoutDocument } from '../../../../entities/booth/layoutApi';

const agent123 = { agentId: 123, boothId: 42 } as never;
const aiObject = { objectId: 'ai-1', type: 'AI_AGENT', position: { x: -2.4, y: 0, z: -2.2 }, rotationY: 0, configId: 123 };

describe('presentationComposer — 플래그 OFF', () => {
  it('A. 게시본 AI_AGENT(123) + 현재 직원 123 → up-to-date 유지', () => {
    const published = fromLayout({ schemaVersion: 1, template: 'PROJECT_EXHIBITION', objects: [aiObject] });
    expect(isUpToDate(published, compose({ aiAgent: agent123, published }))).toBe(true);
  });

  it('A\'. 직원 조회가 null 이어도 게시본 AI_AGENT 만으로는 적용이 뜨지 않는다', () => {
    const published = fromLayout({ schemaVersion: 1, template: 'PROJECT_EXHIBITION', objects: [aiObject] });
    expect(isUpToDate(published, compose({ aiAgent: null, published }))).toBe(true);
  });

  it('B. legacy 가구 때문에 적용해도 AI_AGENT(123) 는 새 문서에 남는다', () => {
    const legacyLayout: LayoutDocument = {
      schemaVersion: 1,
      template: 'PROJECT_EXHIBITION',
      objects: [aiObject, { objectId: 'w', type: 'WALL_PLAIN', position: { x: 0, y: 0, z: 0 }, rotationY: 0 }],
    };
    const published = fromLayout(legacyLayout);
    expect(published.kind).toBe('legacy');
    const next = toLayout(compose({ aiAgent: agent123, published }));
    expect(next.objects).toHaveLength(1);
    expect(next.objects[0]).toMatchObject({ type: 'AI_AGENT', configId: 123 });
  });

  it('미게시 + 직원 있음 → 플래그 OFF 라 AI_AGENT 를 새로 싣지 않는다', () => {
    expect(toLayout(compose({ aiAgent: agent123, published: null })).objects).toEqual([]);
  });
});
