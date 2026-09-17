// presentation ↔ layout 변환과 canonical 비교 (S15P21A604-898).
// 잠그는 것 — ① 기준선은 빈 objects ② 비교는 의미(AI 바인딩)만 보고 좌표·objectId 를 무시한다
// ③ Studio 시절 가구가 남은 게시본은 legacy 라 항상 "적용 필요" 다.
import { describe, expect, it, vi } from 'vitest';

vi.mock('../../model/boothManagementFlags', () => ({ SHOW_AI_ASSET_SECTION: false, PUBLISH_AI_AGENT_BINDING: true }));

import { compose, fromLayout, isUpToDate, toLayout } from '../../model/presentationComposer';
import type { LayoutDocument } from '../../../../entities/booth/layoutApi';

const agent = { agentId: 78, boothId: 42 } as never;
const none = { aiAgent: null, published: null };

describe('presentationComposer', () => {
  it('AI 직원이 없으면 빈 objects 기준선', () => {
    const doc = toLayout(compose(none));
    expect(doc).toEqual({ schemaVersion: 1, template: 'PROJECT_EXHIBITION', objects: [] });
  });

  it('플래그 ON + AI 직원이면 AI_AGENT 하나에 configId=agentId', () => {
    const doc = toLayout(compose({ aiAgent: agent, published: null }));
    expect(doc.objects).toHaveLength(1);
    expect(doc.objects[0]).toMatchObject({ type: 'AI_AGENT', configId: 78, objectId: 'authored-ai-agent' });
  });

  it('좌표·회전·objectId 가 달라도 같은 presentation 이다', () => {
    const published: LayoutDocument = {
      schemaVersion: 1,
      template: 'PROJECT_EXHIBITION',
      objects: [{ objectId: 'ai-1', type: 'AI_AGENT', position: { x: -2.4, y: 0, z: -2.2 }, rotationY: 90, configId: 78 }],
    };
    expect(isUpToDate(fromLayout(published), compose({ aiAgent: agent, published: fromLayout(published) }))).toBe(true);
  });

  it('agentId 가 바뀌면 다르다', () => {
    const published = fromLayout(toLayout(compose({ aiAgent: { agentId: 1 } as never, published: null })));
    expect(isUpToDate(published, compose({ aiAgent: agent, published }))).toBe(false);
  });

  it('Studio 시절 가구가 남은 게시본은 legacy — 항상 적용 필요', () => {
    const published: LayoutDocument = {
      schemaVersion: 1,
      template: 'PROJECT_EXHIBITION',
      objects: [{ objectId: 'w', type: 'WALL_PLAIN', position: { x: 0, y: 0, z: 0 }, rotationY: 0 }],
    };
    const parsed = fromLayout(published);
    expect(parsed.kind).toBe('legacy');
    expect(isUpToDate(parsed, compose({ aiAgent: null, published: parsed }))).toBe(false);
  });

  it('authored 4종만 있는 게시본은 그대로 읽힌다', () => {
    const published: LayoutDocument = {
      schemaVersion: 1,
      template: 'PROJECT_EXHIBITION',
      objects: [
        { objectId: 'p', type: 'PROJECT_PANEL', position: { x: 0, y: 0, z: 0 }, rotationY: 0, configId: 33 },
        { objectId: 's', type: 'SURVEY_KIOSK', position: { x: 0, y: 0, z: 0 }, rotationY: 0 },
      ],
    };
    expect(isUpToDate(fromLayout(published), compose({ aiAgent: null, published: fromLayout(published) }))).toBe(true);
  });
});
